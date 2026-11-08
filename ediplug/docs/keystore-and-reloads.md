# How EDIPLUG's OpenAS2 loads and reloads keys, certificates and partnerships

Upstream OpenAS2 reads `partnerships.xml` and `as2_certs.p12` from disk. In the EDIPLUG build both come from
PostgreSQL and live only in memory. This explains how that works and why it is safe to change data while traffic flows.

## Components

| OpenAS2 component (`config.xml`) | Class | Tables | What it holds |
|---|---|---|---|
| `certificates` id `as2_certs` | `org.openas2.cert.DbCertificateFactory` | `as2_own_key`, `as2_partner_certificate` (+ partner tables for validation) | our key pairs + partner public certs |
| `certificates` id `ssl_trust_certs` | same class, **trust-store mode** | `as2_ssl_trusted_ca` | extra CAs trusted for outbound HTTPS |
| `partnerships` | `org.openas2.partner.DbPartnershipFactory` | `partner`, `partner_attribute`, `partnership`, `partnership_attribute` | partners, partnerships, outbox pollers |

The trusted-CA store needs no separate code: it is the same `DbCertificateFactory`, configured with an empty
`own_key_query` (so no private keys are read), `partner_cert_query` pointing at `as2_ssl_trusted_ca`, and
`validate_partner_aliases="false"`. The only code added for it was "an empty own-key query means no own keys".
OpenAS2's HTTPS sender asks this factory for its keystore on every send (`HttpSenderModule.getHttpOptions`), and
trusts a partner's server if either the JVM trust store or this keystore trusts it.

## Change detection (both factories)

1. Every write to a config table fires a statement-level trigger that increments `config_version.version` for the
   area (`partnerships`, `certificates`, `ssl_trust`). The increment commits with the writer's transaction.
2. Every 30 s (`*.polling.interval`) each factory runs only `SELECT version FROM config_version WHERE area = ...`.
3. If the number is unchanged nothing else happens. If it changed, or every `full_refresh_interval` (3600 s) as a
   safety net, the factory reloads.

## The keystore swap

A Java `KeyStore` object holds entries. OpenAS2 code never keeps one: every lookup calls
`certFx.getKeyStore()` / `getCertificate(alias)` / `getPrivateKey(alias)`, which read the factory's
`keyStore` field (declared `volatile` in `X509CertificateFactory`).

On reload the factory:

1. reads all tables **in one database snapshot** (`REPEATABLE READ`), so keys, certificates and partner aliases
   are consistent with each other and with the version number;
2. builds a **brand-new, empty** `KeyStore` and fills it: own keys are decrypted (`AS2_KEY_ENCRYPTION_PASSWORD`),
   checked against their certificate (sign/verify test) and chain; partner certs are parsed;
3. validates the result against the partner tables and re-reads every entry from the new keystore;
4. **publishes it with one field assignment** (`setKeyStore(newKs)`).

Consequences:
- A message being processed either sees the old keystore or the new one, never a half-built one.
- A thread that already fetched the old object finishes with it; the old object is garbage collected afterwards.
- Nothing is paused: receiving, sending and MDNs carry on during the reload.
- Stock OpenAS2 instead reloads a `.p12` *in place* (`ks.load()` on the live object), so the swap is strictly safer.

What can fail and what happens:

| Problem | At startup | On a reload |
|---|---|---|
| Database unreachable / query error | OpenAS2 does not start | current keystore stays |
| Own key unencrypted, wrong passphrase, key/cert mismatch, broken chain, alias in both tables, no own keys | OpenAS2 does not start | current keystore stays |
| Partner cert missing / unparsable / contains a private key; alias of a partner, partnership or `x509_alias_fallback` unresolved; partnership with no private key on either side | logged, that partner is skipped, server starts | logged; an unparsable update keeps the certificate already loaded for that alias |
| Expired / not yet valid certificate | loaded and logged | loaded and logged |

Partner-level problems behave like stock OpenAS2: only the traffic that needs that certificate fails, per message.
They are logged at ERROR (which triggers the alert email) when first seen and at WARN while they persist; repeated
fatal reload failures are also ERROR once, then WARN. `DbCertificateFactory.getLoadProblems()` returns the current list.

## Partnership reloads

1. Read the whole state in one snapshot; nothing is touched if the read fails (pollers keep running).
2. **Reload certificates first** (`DbCertificateFactory.refreshIfChanged()`), so a partner and its key written in
   the same transaction become usable together.
3. Render every partnership's outbox poller configuration and compare it with the running one. Only pollers whose
   configuration changed (or that were added/removed) are stopped and started; a stopped poller lets a file that is
   being sent finish first. Unchanged pollers keep running.
4. Swap the partnership list in one assignment. Inbound receiving never pauses.

Log lines to look for:
- `Certificate version changed from N to M, reloading keystore`
- `Loaded X own key pairs and Y partner certificates (version M) into keystore as2_certs ...`
- `Partnership pollers: A kept running, B (re)started, C removed`

## Certificate rotation with `x509_alias_fallback`

New certificates are added as **new rows under new aliases**; existing rows are not overwritten. The partner's
`x509_alias_fallback` points at the new alias during the overlap, and primary/fallback are swapped at the agreed
cut-over date. Inbound decryption and signature/MDN verification try the fallback immediately; outbound signing and
encryption use the primary first and the fallback only on the resend after the partner rejects the message in its
MDN. Promotion is manual (no automatic switch in OpenAS2). The validation requires every fallback alias to exist and
to be the same kind (own key / partner certificate) as its primary.
