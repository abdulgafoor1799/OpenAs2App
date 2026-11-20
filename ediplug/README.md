# EDIPLUG OpenAS2 build

OpenAS2 4.15 with EDIPLUG's configuration baked into the image. Runtime data lives in PostgreSQL:

| What | Where | Who writes it | How OpenAS2 picks up changes |
|---|---|---|---|
| Static config (`config.xml`, `openas2.properties`, `logback.xml`, CA certs) | `ediplug/config/` → image `config_template` | this repo | rebuild image |
| Runtime property changes (any `openas2.properties` key) | `override/openas2.properties` mounted at `/opt/openas2/override` | ops (per environment) | container restart |
| Partners and partnerships | tables `partner`, `partner_attribute`, `partnership`, `partnership_attribute` | EDIPLUG backend | version counter polled every 30s |
| Key pairs of the AS2 IDs we host (own IDs, operated suppliers, test IDs) | table `as2_own_key` (private key as encrypted PKCS#8 PEM + cert chain) | EDIPLUG backend | version counter polled every 30s |
| Partner (retailer) public certificates | table `as2_partner_certificate` | EDIPLUG backend | version counter polled every 30s |
| Extra CAs trusted for outbound HTTPS to partners | table `as2_ssl_trusted_ca` | EDIPLUG backend | version counter polled every 30s |
| Secrets (DB password, key-encryption passphrase, keystore password, SMTP) | environment variables | ops | container restart |

OpenAS2 only **reads** these tables (DB role `openas2` has `SELECT` only); its partner/partnership/cert
modification commands are not used. A trigger on every table increments `config_version.version` for the
area (`partnerships` / `certificates` / `ssl_trust`), and OpenAS2 reloads only when that number changes. A full reload
also happens every hour as a safety net.

Code changes on top of upstream:
- `DbPartnershipFactory`: optional `version_query`/`full_refresh_interval`; reads the whole DB state before
  touching pollers, so a DB error keeps the current partnerships and outbox pollers running.
- `DbCertificateFactory` (new, read-only): own key pairs from `as2_own_key` + partner certs from
  `as2_partner_certificate`, validated against the partner tables, assembled into a new in-memory keystore and
  published atomically. No keystore file exists anywhere. Database and own-key errors stop the server at startup
  (a reload keeps the current keystore); partner-level problems only affect that partner (see Validation).
- `X509CertificateFactory.keyStore` is `volatile`.
- `XMLSession` no longer logs property values (they include secrets).
- Container entrypoint `ediplug/bin/ediplug-entrypoint.sh`: runtime `openas2.properties` override and a data
  directory write check, then the stock `start-container.sh`.

## Files in this folder

| Path | Purpose |
|---|---|
| `Dockerfile` | builds the image: Maven build + tests (runs once, natively, for every target architecture), then the JRE runtime with `config/` baked into `/opt/openas2/config_template` |
| `Dockerfile.dockerignore` | build context for that Dockerfile; leaves out docs, deploy files and SQL so editing them does not rebuild Java |
| `bin/ediplug-entrypoint.sh` | container entrypoint: fills `config/` on a fresh container, builds the effective `openas2.properties` (image file + optional override), checks the data directory, then starts OpenAS2 |
| `config/config.xml` | OpenAS2 4.15 configuration: modules, pollers, `DbPartnershipFactory`, two `DbCertificateFactory` stores (`as2_certs`, `ssl_trust_certs`), all DB settings via `$properties.*$` |
| `config/openas2.properties` | EDIPLUG values for `config.xml` properties: DB URL from `$ENV{AS2_DB_*}`, polling intervals, folder layouts, Message-ID formats, MDN URL, outbox file filter, health check |
| `config/logback.xml` | logging: console (`docker logs`), daily file in `/opt/openas2/logs`, ERROR e-mail via `SMTP_*` |
| `config/rds-global-bundle.pem` | AWS RDS CA bundle (`AS2_DB_SSLMODE=verify-full`) |
| `db/create_env_databases.sql` | creates one environment's databases (`ediplug-<env>`, `openas2-<env>`), roles, tables, triggers and grants; idempotent |
| `deploy/docker-compose.yml` | server node behind a cloud load balancer: nginx (LB target :80) → OpenAS2, plus autoheal |
| `deploy/docker-compose.tls.yml` | single VM with TLS on the VM (Let's Encrypt): nginx 80/443 with rate limits → OpenAS2, plus autoheal |
| `deploy/nginx/nginx-tls.conf.template` | nginx for that setup: `server_name ${AS2_PUBLIC_HOSTNAME}`, catch-all 444 / TLS reject, per-endpoint rate limits, 50 MB AS2 bodies |
| `deploy/docker-compose.ha.yml` | add-on for the HA setup: publishes nginx's NLB health port 8081 |
| `deploy/scripts/vm-bootstrap.sh` | host preparation: Docker + Compose v2, AWS CLI, certbot + Cloudflare plugin (`--with-nfs` for HA) |
| `deploy/scripts/setup-node.sh` | builds `/opt/ediplug` from git (standalone or `--shared` FSS layout); creates `.env`/override once |
| `deploy/scripts/issue-cert.sh` | Let's Encrypt via Cloudflare DNS-01 + renewal hook (copies certs, reloads nginx) |
| `deploy/scripts/deploy-openas2.sh` | list ECR tags, deploy a tag (CPU check, wait healthy), `--rollback`, `--current` |
| `deploy/scripts/ha-node.sh` | HA: owner file + heartbeat on FSS, start/stop/takeover, split-brain guard, cert renewal on the owner |
| `deploy/iam/ecr-pull-only-policy.json` | IAM policy for the VMs' ECR key: login + pull/list `openas2-ediplug` only |
| `deploy/docker-compose.local.yml` | developer machine (Windows/Linux): OpenAS2 only, ports on localhost |
| `deploy/nginx/nginx.conf` | routes `/receive` (AS2 receive), `/mdn` (async MDN), `/health` to OpenAS2, everything else 403; real client IP from the LB |
| `deploy/.env.example` | every environment variable with placeholders (copy to `.env`, never commit it) |
| `deploy/override/openas2.properties.example` | template for runtime property overrides |
| `tools/p12-to-own-key-sql.sh` | one-off: converts key pairs from a `.p12` into encrypted `as2_own_key` upserts |
| `docs/standalone-openas2-vm-setup.md` | playbook: a public VM node (`as2-test`, `as2-test2`, ...) from a running VM to verified |
| `docs/ha-openas2-oci-setup.md` | playbook: private VMs behind OCI NLB + NAT, shared state and TLS on FSS, failover |
| `docs/runtime-setup.md` | data directory + runtime properties on Windows, Linux VMs and an active-passive pair with shared storage (OCI FSS) |
| `docs/fresh-vm-setup.md` | full first deployment of a node behind a load balancer (RDS, ECR, verification, operations) |
| `docs/keystore-and-reloads.md` | how keys, certificates, trusted CAs and partnerships load and hot-swap |
| `docs/legacy-4.8.2-properties.md` | reference: effective properties of the old 4.8.2 server |

## Further documentation

- `ediplug/docs/runtime-setup.md`: data directory and runtime properties (Windows, Linux VM, active-passive on OCI)
- `ediplug/docs/keystore-and-reloads.md`: how keys, certificates, trusted CAs and partnerships are loaded and hot-swapped
- `ediplug/docs/fresh-vm-setup.md`: bringing up a node on a fresh VM behind a load balancer (AWS RDS + ECR)
- `ediplug/deploy/`: docker-compose.yml (nginx + OpenAS2 + autoheal), docker-compose.local.yml, nginx.conf for a load
  balancer, .env.example, override/

## Build

From the repository root. Needs Docker with buildx; on an ARM64 host, x86 builds also need QEMU
(`sudo apt-get install docker-buildx qemu-user-static`).

```sh
# one architecture (the host's)
docker build -f ediplug/Dockerfile -t openas2-ediplug:arm64-v2 .                                    # with tests
docker build -f ediplug/Dockerfile --build-arg SKIP_TESTS=true -t openas2-ediplug:arm64-v2 .        # quick
# the other architecture (--provenance/--sbom off: no extra untagged attestation entries in ECR)
docker buildx build -f ediplug/Dockerfile --platform linux/amd64 --provenance=false --sbom=false -t openas2-ediplug:x86-v2 --load .
```

| Tag | Architecture | Same as |
|---|---|---|
| `arm64-v2` | linux/arm64 (Ampere, Graviton, Apple silicon) | `openas2:4.15.0-ediplug-s7` |
| `x86-v2` | linux/amd64 (Windows 11 / Docker Desktop on Intel/AMD, x86 VMs) | |

ECR (`openas2-ediplug`) holds exactly these two tags; there is no multi-arch tag and no `latest`, so every host
names its tag explicitly.

## Environment

| Variable | Example |
|---|---|
| `AS2_DB_HOST` / `AS2_DB_PORT` / `AS2_DB_NAME` | `postgres` / `5432` / `openas2` |
| `AS2_DB_USER` / `AS2_DB_PASSWORD` | `openas2` / … |
| `AS2_DB_SSLMODE` | `verify-full` (RDS), `disable` (local test database without TLS) |
| `AS2_DB_SSLROOTCERT` | `/opt/openas2/config/rds-global-bundle.pem` (RDS); another CA: mount its PEM and point here |
| `AS2_KEY_ENCRYPTION_PASSWORD` | passphrase of the encrypted private keys in `as2_own_key` (never stored in the DB) |
| `AS2_KEYSTORE_PASSWORD` | protects entries of the in-memory keystore only |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_PASS` | error alert email |
| `OPENAS2_PROPERTIES_OVERRIDE_MODE` | `merge` (default: override keys win over the image's) or `replace` (override file only) |
| `OPENAS2_PROPERTIES_OVERRIDE` | override file path, default `/opt/openas2/override/openas2.properties` |

Mounts: `/opt/openas2/data` (inbox/outbox/MDN, writable by UID 1000) and optionally `/opt/openas2/override`
(read-only folder with `openas2.properties`). No config or key files are mounted. Details: `docs/runtime-setup.md`.

## Database setup

One script, `ediplug/db/create_env_databases.sql`, run once per environment (dev, staging, prod ...) as a login with
CREATEDB + CREATEROLE (RDS master user), connected to `postgres`:

```sh
psql "host=<rds> port=5432 user=<admin> dbname=postgres sslmode=verify-full sslrootcert=ap-south-1-bundle.pem" \
     -f ediplug/db/create_env_databases.sql
```

It asks for the environment name, the names (Enter = default) and the passwords of new roles, and creates:

| Object | Default | |
|---|---|---|
| database | `ediplug-<env>` | empty, owned by `ediplug_app_<env>` (the orchestrator creates its own tables) |
| database | `openas2-<env>` | every table OpenAS2 needs, owned by `as2_config_admin_<env>` |
| role | `openas2_<env>` | OpenAS2 container: SELECT on config/cert tables, SELECT/INSERT/UPDATE on `msg_metadata` |
| role | `as2_config_admin_<env>` | single writer of the AS2 configuration |
| role | `ediplug_app_<env>` | orchestrator application database owner |

Non-interactive: `EDIPLUG_ENV=dev EDIPLUG_APP_PASSWORD=... OPENAS2_RUNTIME_PASSWORD=... OPENAS2_ADMIN_PASSWORD=... psql ... -f ...`
(or `-v env=dev -v as2_db=...`). It is idempotent: existing databases, roles and tables are kept.

## Migrating existing files

Partnerships (XML → DB), using the tool shipped in the image:

```sh
docker run --rm --network <net> -v $PWD:/mig:ro --entrypoint /opt/openas2/bin/upgrade/migrate_partnerships_to_db.sh \
  openas2-ediplug:arm64-v2 /mig/partnerships.xml "jdbc:postgresql://<host>:5432/openas2?sslmode=verify-full&sslrootcert=/opt/openas2/config_template/rds-global-bundle.pem" \
  <admin user> <password> --dry-run      # then again without --dry-run
```

Own key pairs from an existing PKCS12 keystore (one-off; plaintext keys stay in the container's tmpfs):

```sh
docker run --rm --tmpfs /tmp -e P12_PASSWORD -e AS2_KEY_ENCRYPTION_PASSWORD -v $PWD/ediplug/tools:/tools:ro \
  -v /path/to/as2_certs.p12:/in.p12:ro --entrypoint sh openas2-ediplug:arm64-v2 \
  /tools/p12-to-own-key-sql.sh /in.p12 ediplug_as2 ediplug_sana_as2 | psql -v ON_ERROR_STOP=1 -d openas2
```

Partner certificates: export the PEM (`keytool -exportcert -rfc ...`) and insert it as below.

## Validation (startup and every reload)

The factory reads the certificate tables and the partner tables in one snapshot.

**Fatal** (startup stops; a reload keeps the current keystore): the database cannot be read; an own key is
unencrypted, cannot be decrypted, does not match its certificate or has a broken chain; an alias is in both tables;
`as2_own_key` has no enabled rows.

**Partner level** (logged, only that partner's traffic fails per message - stock OpenAS2 behaviour): a partner
certificate that cannot be parsed or contains a private key is skipped (on a reload the certificate already loaded
for the alias is kept); a partner / partnership / `x509_alias_fallback` alias with no certificate; a partner without
`x509_alias`; a partnership with no private key on either side; a fallback of a different kind than its primary
(own key vs partner certificate); expired or not-yet-valid certificates (still loaded: OpenAS2 itself rejects
signatures from an expired partner certificate unless `as2_sign_allow_expired_certificate=true`). These are logged
at ERROR when first seen and at WARN while they persist.

Warnings: expiring within 30 days, RSA < 2048 bits, duplicate certificates, rows not referenced by any partner.
The database also rejects an alias present in both tables, unencrypted private keys and private key material in
partner rows.

## Reloads

Every 30s each factory reads only its version counter. On a change:
- certificates: a new keystore is built, validated and swapped in; nothing is paused;
- partnerships: the certificate store is checked first (so a partner and its key written in one transaction go live
  together), then only outbox pollers whose configuration changed are restarted; receiving is never paused.

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

OpenAS2 logs `Certificate version changed ... reloading keystore` within ~30s. To disable a certificate set
`enabled = false`.

## Rollback

Every stage is a separate image tag; the DB migrations only add objects. Roll back by redeploying the previous
image tag with the previous compose file (and, before Stage 3, the `as2_certs.p12` mount).
