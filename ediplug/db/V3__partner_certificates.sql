-- V3: partner public certificates for org.openas2.cert.DbCertificateFactory.
-- Public certificates only: our own private keys are NOT stored in the database (they come from the
-- own_keys.p12 secret mounted into the container).
--   psql -v ON_ERROR_STOP=1 -d openas2 -f V3__partner_certificates.sql
-- Idempotent. Requires V2 (config_version, bump_config_version).

BEGIN;

CREATE TABLE IF NOT EXISTS as2_partner_certificate
(
    -- Keystore alias; referenced by the partner's x509_alias attribute
    alias              varchar(255) PRIMARY KEY,
    -- PEM encoded X.509 certificate (first certificate is the partner's; issuers may follow)
    cert_pem           text        NOT NULL CHECK (cert_pem LIKE '%-----BEGIN CERTIFICATE-----%'),
    -- Informational, maintained by the backend that uploads the certificate
    sha256_fingerprint varchar(95),
    subject_dn         text,
    not_before         timestamptz,
    not_after          timestamptz,
    enabled            boolean     NOT NULL DEFAULT true,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE as2_partner_certificate OWNER TO as2_config_admin;

CREATE INDEX IF NOT EXISTS as2_partner_certificate_not_after ON as2_partner_certificate (not_after);

DROP TRIGGER IF EXISTS as2_partner_certificate_bump_version ON as2_partner_certificate;
CREATE TRIGGER as2_partner_certificate_bump_version
    AFTER INSERT OR UPDATE OR DELETE ON as2_partner_certificate
    FOR EACH STATEMENT EXECUTE FUNCTION bump_config_version('certificates');
DROP TRIGGER IF EXISTS as2_partner_certificate_bump_version_truncate ON as2_partner_certificate;
CREATE TRIGGER as2_partner_certificate_bump_version_truncate
    AFTER TRUNCATE ON as2_partner_certificate
    FOR EACH STATEMENT EXECUTE FUNCTION bump_config_version('certificates');

REVOKE ALL ON as2_partner_certificate FROM PUBLIC;
GRANT SELECT ON as2_partner_certificate TO openas2;

COMMIT;
