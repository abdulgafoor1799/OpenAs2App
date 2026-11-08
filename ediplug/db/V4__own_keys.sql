-- V4: key pairs for the AS2 identities hosted by this server (own IDs, operated suppliers, test IDs),
-- read by org.openas2.cert.DbCertificateFactory together with as2_partner_certificate (V3).
--   psql -v ON_ERROR_STOP=1 -d openas2 -f V4__own_keys.sql
-- Idempotent. Requires V2 (config_version, bump_config_version) and V3.
--
-- private_key_pem must be an ENCRYPTED PKCS#8 PEM ("-----BEGIN ENCRYPTED PRIVATE KEY-----"), encrypted with the
-- passphrase OpenAS2 receives in AS2_KEY_ENCRYPTION_PASSWORD. The passphrase is never stored in the database.
-- Examples: openssl pkcs8 -topk8 -v2 aes-256-cbc -v2prf hmacWithSHA256 -in key.pem -passout env:PASS
--           Python: key.private_bytes(PEM, PKCS8, BestAvailableEncryption(passphrase))

BEGIN;

CREATE TABLE IF NOT EXISTS as2_own_key
(
    -- Keystore alias; referenced by the partner's x509_alias attribute
    alias              varchar(255) PRIMARY KEY,
    -- Encrypted PKCS#8 private key
    private_key_pem    text        NOT NULL CHECK (private_key_pem LIKE '%-----BEGIN ENCRYPTED PRIVATE KEY-----%'),
    -- PEM certificate chain, leaf (the certificate given to trading partners) first
    cert_chain_pem     text        NOT NULL CHECK (cert_chain_pem LIKE '%-----BEGIN CERTIFICATE-----%'
                                                   AND cert_chain_pem NOT LIKE '%PRIVATE KEY%'),
    -- Informational, maintained by the backend that creates the key pair
    sha256_fingerprint varchar(95),
    subject_dn         text,
    not_before         timestamptz,
    not_after          timestamptz,
    enabled            boolean     NOT NULL DEFAULT true,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE as2_own_key OWNER TO as2_config_admin;

CREATE INDEX IF NOT EXISTS as2_own_key_not_after ON as2_own_key (not_after);

DROP TRIGGER IF EXISTS as2_own_key_bump_version ON as2_own_key;
CREATE TRIGGER as2_own_key_bump_version
    AFTER INSERT OR UPDATE OR DELETE ON as2_own_key
    FOR EACH STATEMENT EXECUTE FUNCTION bump_config_version('certificates');
DROP TRIGGER IF EXISTS as2_own_key_bump_version_truncate ON as2_own_key;
CREATE TRIGGER as2_own_key_bump_version_truncate
    AFTER TRUNCATE ON as2_own_key
    FOR EACH STATEMENT EXECUTE FUNCTION bump_config_version('certificates');

-- An alias may exist in only one of the two certificate tables (compared case-insensitively, like PKCS12 aliases).
CREATE OR REPLACE FUNCTION check_as2_alias_unique() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS
$$
BEGIN
    IF TG_TABLE_NAME = 'as2_own_key' THEN
        IF EXISTS (SELECT 1 FROM as2_partner_certificate WHERE lower(alias) = lower(NEW.alias))
           OR EXISTS (SELECT 1 FROM as2_own_key WHERE lower(alias) = lower(NEW.alias) AND alias <> NEW.alias) THEN
            RAISE EXCEPTION 'alias "%" is already used in as2_partner_certificate or differs only by case', NEW.alias;
        END IF;
    ELSE
        IF EXISTS (SELECT 1 FROM as2_own_key WHERE lower(alias) = lower(NEW.alias))
           OR EXISTS (SELECT 1 FROM as2_partner_certificate WHERE lower(alias) = lower(NEW.alias) AND alias <> NEW.alias) THEN
            RAISE EXCEPTION 'alias "%" is already used in as2_own_key or differs only by case', NEW.alias;
        END IF;
    END IF;
    RETURN NEW;
END
$$;
ALTER FUNCTION check_as2_alias_unique() OWNER TO as2_config_admin;

DROP TRIGGER IF EXISTS as2_own_key_alias_unique ON as2_own_key;
CREATE TRIGGER as2_own_key_alias_unique BEFORE INSERT OR UPDATE OF alias ON as2_own_key
    FOR EACH ROW EXECUTE FUNCTION check_as2_alias_unique();
DROP TRIGGER IF EXISTS as2_partner_certificate_alias_unique ON as2_partner_certificate;
CREATE TRIGGER as2_partner_certificate_alias_unique BEFORE INSERT OR UPDATE OF alias ON as2_partner_certificate
    FOR EACH ROW EXECUTE FUNCTION check_as2_alias_unique();

-- Partner rows hold public certificates only
ALTER TABLE as2_partner_certificate DROP CONSTRAINT IF EXISTS as2_partner_certificate_no_private_key;
ALTER TABLE as2_partner_certificate ADD CONSTRAINT as2_partner_certificate_no_private_key
    CHECK (cert_pem NOT LIKE '%PRIVATE KEY%');

REVOKE ALL ON as2_own_key FROM PUBLIC;
GRANT SELECT ON as2_own_key TO openas2;

COMMIT;
