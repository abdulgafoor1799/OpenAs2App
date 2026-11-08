-- V5: extra certificates trusted for outbound HTTPS connections to trading partners (OpenAS2 "ssl_trust_certs"),
-- read by org.openas2.cert.DbCertificateFactory in trust-store mode. A partner's HTTPS server is trusted when either
-- the JVM's default trust store or this table trusts it (OpenAS2 4.14+), so only add CAs, intermediates or
-- self-signed server certificates that public CAs do not cover. Inbound TLS is terminated by nginx and not affected.
--   psql -v ON_ERROR_STOP=1 -d openas2 -f V5__ssl_trusted_ca.sql
-- Idempotent. Requires V2 (config_version, bump_config_version).

BEGIN;

CREATE TABLE IF NOT EXISTS as2_ssl_trusted_ca
(
    alias              varchar(255) PRIMARY KEY,
    cert_pem           text        NOT NULL CHECK (cert_pem LIKE '%-----BEGIN CERTIFICATE-----%'
                                                   AND cert_pem NOT LIKE '%PRIVATE KEY%'),
    -- Informational, maintained by the backend
    description        text,
    sha256_fingerprint varchar(95),
    subject_dn         text,
    not_before         timestamptz,
    not_after          timestamptz,
    enabled            boolean     NOT NULL DEFAULT true,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE as2_ssl_trusted_ca OWNER TO as2_config_admin;

INSERT INTO config_version (area) VALUES ('ssl_trust') ON CONFLICT (area) DO NOTHING;

DROP TRIGGER IF EXISTS as2_ssl_trusted_ca_bump_version ON as2_ssl_trusted_ca;
CREATE TRIGGER as2_ssl_trusted_ca_bump_version
    AFTER INSERT OR UPDATE OR DELETE ON as2_ssl_trusted_ca
    FOR EACH STATEMENT EXECUTE FUNCTION bump_config_version('ssl_trust');
DROP TRIGGER IF EXISTS as2_ssl_trusted_ca_bump_version_truncate ON as2_ssl_trusted_ca;
CREATE TRIGGER as2_ssl_trusted_ca_bump_version_truncate
    AFTER TRUNCATE ON as2_ssl_trusted_ca
    FOR EACH STATEMENT EXECUTE FUNCTION bump_config_version('ssl_trust');

REVOKE ALL ON as2_ssl_trusted_ca FROM PUBLIC;
GRANT SELECT ON as2_ssl_trusted_ca TO openas2;

COMMIT;
