# EDIPLUG OpenAS2 build

OpenAS2 4.15 with EDIPLUG's configuration baked into the image. Runtime data lives in PostgreSQL:

| What | Where | Who writes it | How OpenAS2 picks up changes |
|---|---|---|---|
| Static config (`config.xml`, `openas2.properties`, `logback.xml`, CA certs) | `ediplug/config/` → image `config_template` | this repo | rebuild image |
| Partners and partnerships | tables `partner`, `partner_attribute`, `partnership`, `partnership_attribute` | EDIPLUG backend | version counter polled every 10s |
| Partner public certificates | table `as2_partner_certificate` | EDIPLUG backend | version counter polled every 10s |
| Our private keys | `own_keys.p12` secret mounted at `/opt/openas2/secrets/own_keys.p12` | ops (Secrets Manager later) | file change detected every 10s |
| Secrets (DB password, keystore password, SMTP) | environment variables | ops | container restart |

OpenAS2 only **reads** these tables (DB role `openas2` has `SELECT` only); its partner/partnership/cert
modification commands are not used. A trigger on every table increments `config_version.version` for the
area (`partnerships` / `certificates`), and OpenAS2 reloads only when that number changes. A full reload
also happens every hour as a safety net.

Code changes on top of upstream:
- `DbPartnershipFactory`: optional `version_query`/`full_refresh_interval`; reads the whole DB state before
  touching pollers, so a DB error keeps the current partnerships and outbox pollers running.
- `DbCertificateFactory` (new, read-only): own keys from a PKCS12 file + partner certs from the DB, assembled into
  a new in-memory keystore on every reload and published atomically. A bad certificate row never removes a
  certificate that is already loaded.
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
| `AS2_KEYSTORE_PASSWORD` | password of `own_keys.p12` |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_PASS` | error alert email |

Mounts: `/opt/openas2/data` (inbox/outbox/MDN, writable by UID 1000) and
`/opt/openas2/secrets/own_keys.p12` (read-only). The config directory is **not** mounted.

## Database setup (new database, e.g. RDS)

Run as the master/superuser, in order (all scripts are idempotent):

```sh
psql -v ON_ERROR_STOP=1 -d openas2 -f ediplug/db/V1__partnership_tables.sql
psql -v ON_ERROR_STOP=1 -d openas2 -f ediplug/db/V2__config_version.sql
psql -v ON_ERROR_STOP=1 -d openas2 -f ediplug/db/V3__partner_certificates.sql
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

Keystore split (`as2_certs.p12` → `own_keys.p12` + partner rows):

```sh
for a in ediplug_as2 ediplug_sana_as2; do
  keytool -importkeystore -noprompt -srckeystore as2_certs.p12 -srcstoretype PKCS12 -srcstorepass "$P" -srcalias $a \
    -destkeystore own_keys.p12 -deststoretype PKCS12 -deststorepass "$P" -destkeypass "$P"
done
keytool -exportcert -rfc -keystore as2_certs.p12 -storetype PKCS12 -storepass "$P" -alias test_as2 > test_as2.pem
# then INSERT the PEM into as2_partner_certificate (alias = the partner's x509_alias)
```

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
