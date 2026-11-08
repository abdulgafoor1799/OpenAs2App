# EDIPLUG OpenAS2 build

OpenAS2 4.15 with EDIPLUG's configuration baked into the image. Runtime data lives in PostgreSQL:

| What | Where | Who writes it | How OpenAS2 picks up changes |
|---|---|---|---|
| Static config (`config.xml`, `openas2.properties`, `logback.xml`, CA certs) | `ediplug/config/` → image `config_template` | this repo | rebuild image |
| Partners and partnerships | tables `partner`, `partner_attribute`, `partnership`, `partnership_attribute` | EDIPLUG backend | version counter polled every 10s |
| Key pairs of the AS2 IDs we host (own IDs, operated suppliers, test IDs) | table `as2_own_key` (private key as encrypted PKCS#8 PEM + cert chain) | EDIPLUG backend | version counter polled every 10s |
| Partner (retailer) public certificates | table `as2_partner_certificate` | EDIPLUG backend | version counter polled every 10s |
| Secrets (DB password, key-encryption passphrase, keystore password, SMTP) | environment variables | ops | container restart |

OpenAS2 only **reads** these tables (DB role `openas2` has `SELECT` only); its partner/partnership/cert
modification commands are not used. A trigger on every table increments `config_version.version` for the
area (`partnerships` / `certificates`), and OpenAS2 reloads only when that number changes. A full reload
also happens every hour as a safety net.

Code changes on top of upstream:
- `DbPartnershipFactory`: optional `version_query`/`full_refresh_interval`; reads the whole DB state before
  touching pollers, so a DB error keeps the current partnerships and outbox pollers running.
- `DbCertificateFactory` (new, read-only): own key pairs from `as2_own_key` + partner certs from
  `as2_partner_certificate`, validated against the partner tables, assembled into a new in-memory keystore and
  published atomically. No keystore file exists anywhere. Any validation error stops the server at startup;
  on a reload it keeps the current keystore.
- `X509CertificateFactory.keyStore` is `volatile`.

## Build

```sh
docker build -f ediplug/Dockerfile -t openas2:4.15.0-ediplug-s3 .                              # with tests
docker build -f ediplug/Dockerfile --build-arg SKIP_TESTS=true -t openas2:4.15.0-ediplug-s3 .  # quick
```

## Environment

| Variable | Example |
|---|---|
| `AS2_DB_HOST` / `AS2_DB_PORT` / `AS2_DB_NAME` | `postgres` / `5432` / `openas2` |
| `AS2_DB_USER` / `AS2_DB_PASSWORD` | `openas2` / … |
| `AS2_DB_SSLMODE` | `verify-ca` (local container), `verify-full` (RDS) |
| `AS2_DB_SSLROOTCERT` | `/opt/openas2/config/db-ca.pem` (local), `/opt/openas2/config/rds-global-bundle.pem` (RDS) |
| `AS2_KEY_ENCRYPTION_PASSWORD` | passphrase of the encrypted private keys in `as2_own_key` (never stored in the DB) |
| `AS2_KEYSTORE_PASSWORD` | protects entries of the in-memory keystore only |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_PASS` | error alert email |

Mounts: `/opt/openas2/data` only (inbox/outbox/MDN, writable by UID 1000). No config or key files are mounted.

## Database setup (new database, e.g. RDS)

Run as the master/superuser, in order (all scripts are idempotent):

```sh
psql -v ON_ERROR_STOP=1 -d openas2 -f ediplug/db/V1__partnership_tables.sql
psql -v ON_ERROR_STOP=1 -d openas2 -f ediplug/db/V2__config_version.sql
psql -v ON_ERROR_STOP=1 -d openas2 -f ediplug/db/V3__partner_certificates.sql
psql -v ON_ERROR_STOP=1 -d openas2 -f ediplug/db/V4__own_keys.sql
psql -d openas2 -c '\password openas2'            # runtime role
psql -d openas2 -c '\password as2_config_admin'   # backend / migrations role
```

## Migrating existing files

Partnerships (XML → DB), using the tool shipped in the image:

```sh
docker run --rm --network <net> -v $PWD:/mig:ro --entrypoint /opt/openas2/bin/upgrade/migrate_partnerships_to_db.sh \
  openas2:4.15.0-ediplug-s3 /mig/partnerships.xml "jdbc:postgresql://<host>:5432/openas2?sslmode=verify-ca&sslrootcert=/opt/openas2/config_template/db-ca.pem" \
  <admin user> <password> --dry-run      # then again without --dry-run
```

Own key pairs from an existing PKCS12 keystore (one-off; plaintext keys stay in the container's tmpfs):

```sh
docker run --rm --tmpfs /tmp -e P12_PASSWORD -e AS2_KEY_ENCRYPTION_PASSWORD -v $PWD/ediplug/tools:/tools:ro \
  -v /path/to/as2_certs.p12:/in.p12:ro --entrypoint sh openas2:4.15.0-ediplug-s4 \
  /tools/p12-to-own-key-sql.sh /in.p12 ediplug_as2 ediplug_sana_as2 | psql -v ON_ERROR_STOP=1 -d openas2
```

Partner certificates: export the PEM (`keytool -exportcert -rfc ...`) and insert it as below.

## Validation (startup and every reload)

The factory reads both certificate tables and the partner tables in one snapshot and checks:
own keys are encrypted, decrypt, match their certificate and have a valid chain; partner rows hold a parseable
public certificate only; no certificate is expired or not yet valid (`fail_on_expired`); aliases are unique
across both tables; every partner `x509_alias` and every partnership sender/receiver override resolves to an
enabled row; every partnership has a private key on at least one side. Expiring within 30 days, small RSA keys,
duplicate certificates and unreferenced rows are warnings. All errors are logged together; at startup the server
exits, on a reload the current keystore stays in use. The database also rejects an alias present in both tables,
unencrypted private keys and private key material in partner rows.

## Adding an own key pair (what the backend does for a new supplier)

Generate the key pair and a self-signed certificate, encrypt the private key with `AS2_KEY_ENCRYPTION_PASSWORD`
(e.g. Python `key.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, BestAvailableEncryption(passphrase))`), insert
the certificate row first and then the partner whose `x509_alias` points at it, or do both in one transaction.

## Adding a partner certificate (what the backend does)

```sql
INSERT INTO as2_partner_certificate (alias, cert_pem, sha256_fingerprint, subject_dn, not_before, not_after)
VALUES ('rithumas2', $pem$-----BEGIN CERTIFICATE-----
...
-----END CERTIFICATE-----$pem$, '<fingerprint>', '<subject>', '<not before>', '<not after>')
ON CONFLICT (alias) DO UPDATE SET cert_pem = EXCLUDED.cert_pem, updated_at = now();
```

OpenAS2 logs `Certificate version changed ... reloading keystore` within ~10s. To disable a certificate set
`enabled = false`.

## Rollback

Every stage is a separate image tag; the DB migrations only add objects. Roll back by redeploying the previous
image tag with the previous compose file (and, before Stage 3, the `as2_certs.p12` mount).
