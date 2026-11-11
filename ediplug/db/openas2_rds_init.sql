-- =====================================================================================================================
-- EDIPLUG OpenAS2 4.15: database initialisation for AWS RDS PostgreSQL (16+)
--
-- Creates:
--   database  openas2 (default schema: public)
--   role      openas2           runtime login used by the OpenAS2 container (AS2_DB_USER=openas2)
--                               - can connect to database openas2 only
--                               - SELECT only on partner/partnership/certificate tables
--                               - SELECT, INSERT, UPDATE on msg_metadata (message tracking); owns nothing
--   role      as2_config_admin  owner of all tables and single writer of the configuration tables
--                               (migrations, EDIPLUG backend / orchestrator)
--
-- OpenAS2 does NOT create any of these tables. They must exist before the container starts, otherwise the
-- certificate factory fails at startup.
--
-- Usage (as the RDS master user, connected to database "postgres"):
--   psql "...dbname=postgres sslmode=verify-full sslrootcert=global-bundle.pem" -v ON_ERROR_STOP=1 \
--        -v openas2_password='...' -v admin_password='...' -f openas2_rds_init.sql
--
-- Optional variables (defaults shown), e.g. for a dev database on the same instance:
--   -v db_name=openas2  -v runtime_role=openas2  -v admin_role=as2_config_admin
--   psql ... -v db_name=openas2-dev -v runtime_role=openas2_dev -v admin_role=as2_config_admin_dev ...
-- The OpenAS2 container then uses AS2_DB_NAME=<db_name>, AS2_DB_USER=<runtime_role>; the backend uses <admin_role>.
--
-- Idempotent: safe to run again (existing objects are kept; passwords are only set on first creation).
-- Pass both -v password variables on every run, also re-runs: psql cannot parse the script without them.
-- =====================================================================================================================


-- ---------------------------------------------------------------------------------------------------------------------
-- PART 1: cluster level (connected to database "postgres")
-- ---------------------------------------------------------------------------------------------------------------------

\if :{?db_name}
\else
\set db_name openas2
\endif
\if :{?runtime_role}
\else
\set runtime_role openas2
\endif
\if :{?admin_role}
\else
\set admin_role as2_config_admin
\endif

-- Roles. Passwords come from the psql variables, so they are not stored in this file.
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'runtime_role', :'openas2_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'runtime_role') \gexec

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'admin_role', :'admin_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'admin_role') \gexec

-- The RDS master user is not a superuser. It must be a member of a role to make that role the owner of objects
-- (ALTER ... OWNER TO) in part 2.
GRANT :"runtime_role", :"admin_role" TO CURRENT_USER;

-- Database (CREATE DATABASE cannot run inside a transaction or DO block, hence \gexec)
SELECT format('CREATE DATABASE %I ENCODING ''UTF8''', :'db_name')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db_name') \gexec

-- Only our two roles (and the master user) may connect to this database
REVOKE ALL    ON DATABASE :"db_name" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db_name" TO :"runtime_role", :"admin_role";


-- ---------------------------------------------------------------------------------------------------------------------
-- PART 2: inside database openas2
-- ---------------------------------------------------------------------------------------------------------------------
\connect :"db_name"

BEGIN;

-- Schema: use "public" (OpenAS2 queries unqualified table names). Nobody else gets access to it.
-- Only as2_config_admin may create objects. A non-superuser can make a role the owner of a table only if that role
-- has CREATE here, so every table is owned by as2_config_admin.
REVOKE ALL   ON SCHEMA public FROM PUBLIC;
GRANT USAGE  ON SCHEMA public TO :"runtime_role", :"admin_role";
GRANT CREATE ON SCHEMA public TO :"admin_role";


-- =====================================================================================================================
-- Message tracking (OpenAS2 DbTrackingModule). One row per AS2 message, updated as it is sent / MDN'd.
-- Columns are exactly the module's field list; it only runs SELECT / INSERT / UPDATE (no DELETE, no DDL).
-- Includes the two columns added in 4.15 (sent_file_name, mdn_file_path).
-- =====================================================================================================================
CREATE TABLE IF NOT EXISTS msg_metadata
(
    id                        serial       PRIMARY KEY,
    msg_id                    text         NOT NULL,
    prior_msg_id              text,
    mdn_id                    text,
    direction                 varchar(25),
    is_resend                 varchar(1),
    resend_count              integer,
    sender_id                 varchar(255) NOT NULL,
    receiver_id               varchar(255) NOT NULL,
    status                    varchar(255),
    state                     varchar(255),
    signature_algorithm       varchar(255),
    encryption_algorithm      varchar(255),
    compression               varchar(255),
    file_name                 varchar(255),
    content_type              varchar(255),
    content_transfer_encoding varchar(255),
    mdn_mode                  varchar(255),
    mdn_response              text,
    state_msg                 text,
    create_dt                 timestamp    DEFAULT CURRENT_TIMESTAMP,
    update_dt                 timestamp    DEFAULT CURRENT_TIMESTAMP,
    sent_file_name            varchar(255),
    mdn_file_path             text
);
CREATE UNIQUE INDEX IF NOT EXISTS msg_id_unique         ON msg_metadata (msg_id);
-- The table grows forever: indexes for the message list (by date) and the MDN lookup (by payload file name)
CREATE INDEX        IF NOT EXISTS msg_metadata_create_dt ON msg_metadata (create_dt);
CREATE INDEX        IF NOT EXISTS msg_metadata_file_name ON msg_metadata (file_name);


-- =====================================================================================================================
-- V1: partnership store, read by org.openas2.partner.DbPartnershipFactory.
-- Column names are unquoted so they match the factory's unquoted upper-case SQL.
-- =====================================================================================================================

-- One row per trading partner (our hosted AS2 IDs and remote partners alike)
CREATE TABLE IF NOT EXISTS partner
(
    id   integer      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name varchar(255) NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS partner_name_unique ON partner (name);

-- Partner attributes: as2_id, x509_alias (keystore alias), x509_alias_fallback (rotation), email, ...
CREATE TABLE IF NOT EXISTS partner_attribute
(
    id              integer       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    partner_id      integer       NOT NULL REFERENCES partner (id) ON DELETE CASCADE,
    attribute_name  varchar(255)  NOT NULL,
    attribute_value varchar(4000)
);
CREATE UNIQUE INDEX IF NOT EXISTS partner_attribute_unique ON partner_attribute (partner_id, attribute_name);

-- One row per direction: sender partner -> receiver partner
CREATE TABLE IF NOT EXISTS partnership
(
    id                  integer      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name                varchar(255) NOT NULL,
    sender_partner_id   integer      NOT NULL REFERENCES partner (id),
    receiver_partner_id integer      NOT NULL REFERENCES partner (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS partnership_name_unique ON partnership (name);

-- Partnership attributes by category:
--   attribute    normal settings (as2_url, sign, encrypt, subject, as2_mdn_options, ...)
--   sender       overrides of the sender partner's attributes for this partnership only (e.g. x509_alias)
--   receiver     overrides of the receiver partner's attributes for this partnership only
--   pollerConfig outbox directory poller settings (enabled, mimetype, interval, ...)
-- Note: x509_alias / x509_alias_fallback are only honoured on the partner or as sender/receiver overrides,
-- not in the 'attribute' category.
CREATE TABLE IF NOT EXISTS partnership_attribute
(
    id              integer       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    partnership_id  integer       NOT NULL REFERENCES partnership (id) ON DELETE CASCADE,
    category        varchar(20)   NOT NULL DEFAULT 'attribute'
                    CHECK (category IN ('attribute', 'sender', 'receiver', 'pollerConfig')),
    attribute_name  varchar(255)  NOT NULL,
    attribute_value varchar(4000)
);
CREATE UNIQUE INDEX IF NOT EXISTS partnership_attribute_unique
    ON partnership_attribute (partnership_id, category, attribute_name);


-- =====================================================================================================================
-- V2: change counters. OpenAS2 polls only these rows (every 30 s) and reloads an area when its version changes.
--   partnerships  <- partner, partner_attribute, partnership, partnership_attribute
--   certificates  <- as2_own_key, as2_partner_certificate
--   ssl_trust     <- as2_ssl_trusted_ca
-- =====================================================================================================================
CREATE TABLE IF NOT EXISTS config_version
(
    area       varchar(50) PRIMARY KEY,
    version    bigint      NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO config_version (area) VALUES ('partnerships'), ('certificates'), ('ssl_trust')
ON CONFLICT (area) DO NOTHING;

-- Statement-level trigger function: one increment per statement, visible to OpenAS2 when the writer commits.
-- SECURITY DEFINER so any role allowed to write the config tables can bump the counter.
CREATE OR REPLACE FUNCTION bump_config_version() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS
$$
BEGIN
    UPDATE config_version SET version = version + 1, updated_at = now() WHERE area = TG_ARGV[0];
    RETURN NULL;
END
$$;


-- =====================================================================================================================
-- V3: public certificates of remote trading partners (retailers), read by org.openas2.cert.DbCertificateFactory.
-- Public certificates only: private key material is rejected.
-- =====================================================================================================================
CREATE TABLE IF NOT EXISTS as2_partner_certificate
(
    alias              varchar(255) PRIMARY KEY,   -- = the partner's x509_alias
    cert_pem           text         NOT NULL
                       CHECK (cert_pem LIKE '%-----BEGIN CERTIFICATE-----%'),  -- PEM; issuers may follow the leaf
    sha256_fingerprint varchar(95),                -- informational (maintained by the backend)
    subject_dn         text,                       -- informational
    not_before         timestamptz,                -- informational
    not_after          timestamptz,                -- informational; indexed for expiry alerts
    enabled            boolean      NOT NULL DEFAULT true,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT as2_partner_certificate_no_private_key CHECK (cert_pem NOT LIKE '%PRIVATE KEY%')
);
CREATE INDEX IF NOT EXISTS as2_partner_certificate_not_after ON as2_partner_certificate (not_after);


-- =====================================================================================================================
-- V4: key pairs of the AS2 identities this server hosts (own IDs, operated suppliers, test IDs).
-- private_key_pem must be an ENCRYPTED PKCS#8 PEM, encrypted with the passphrase the container receives in
-- AS2_KEY_ENCRYPTION_PASSWORD. The passphrase is never stored in the database.
--   openssl:  openssl pkcs8 -topk8 -v2 aes-256-cbc -v2prf hmacWithSHA256 -in key.pem -passout env:PASS
--   Python:   key.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, BestAvailableEncryption(passphrase))
-- =====================================================================================================================
CREATE TABLE IF NOT EXISTS as2_own_key
(
    alias              varchar(255) PRIMARY KEY,   -- = our partner's x509_alias
    private_key_pem    text         NOT NULL
                       CHECK (private_key_pem LIKE '%-----BEGIN ENCRYPTED PRIVATE KEY-----%'),
    cert_chain_pem     text         NOT NULL       -- leaf (the certificate given to partners) first
                       CHECK (cert_chain_pem LIKE '%-----BEGIN CERTIFICATE-----%'
                              AND cert_chain_pem NOT LIKE '%PRIVATE KEY%'),
    sha256_fingerprint varchar(95),
    subject_dn         text,
    not_before         timestamptz,
    not_after          timestamptz,
    enabled            boolean      NOT NULL DEFAULT true,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS as2_own_key_not_after ON as2_own_key (not_after);

-- An alias may exist in only one of the two certificate tables, compared case-insensitively
-- (keystore aliases are case-insensitive).
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


-- =====================================================================================================================
-- V5: extra certificates trusted for OUTBOUND HTTPS to partners (OpenAS2 "ssl_trust_certs" keystore). Used in
-- addition to the JVM trust store: add only CAs, intermediates or self-signed partner server certificates that public
-- CAs do not cover. Inbound TLS is terminated by the load balancer / nginx and is not affected.
-- =====================================================================================================================
CREATE TABLE IF NOT EXISTS as2_ssl_trusted_ca
(
    alias              varchar(255) PRIMARY KEY,
    cert_pem           text         NOT NULL
                       CHECK (cert_pem LIKE '%-----BEGIN CERTIFICATE-----%' AND cert_pem NOT LIKE '%PRIVATE KEY%'),
    description        text,
    sha256_fingerprint varchar(95),
    subject_dn         text,
    not_before         timestamptz,
    not_after          timestamptz,
    enabled            boolean      NOT NULL DEFAULT true,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now()
);


-- =====================================================================================================================
-- Triggers
-- =====================================================================================================================

-- Version counters: every INSERT / UPDATE / DELETE / TRUNCATE statement bumps its area once
DO $$
DECLARE
    t    text;
    area text;
BEGIN
    FOR t, area IN VALUES ('partner', 'partnerships'), ('partner_attribute', 'partnerships'),
                          ('partnership', 'partnerships'), ('partnership_attribute', 'partnerships'),
                          ('as2_partner_certificate', 'certificates'), ('as2_own_key', 'certificates'),
                          ('as2_ssl_trusted_ca', 'ssl_trust')
    LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS %I ON %I', t || '_bump_version', t);
        EXECUTE format('CREATE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON %I '
                       'FOR EACH STATEMENT EXECUTE FUNCTION bump_config_version(%L)', t || '_bump_version', t, area);
        EXECUTE format('DROP TRIGGER IF EXISTS %I ON %I', t || '_bump_version_truncate', t);
        EXECUTE format('CREATE TRIGGER %I AFTER TRUNCATE ON %I '
                       'FOR EACH STATEMENT EXECUTE FUNCTION bump_config_version(%L)', t || '_bump_version_truncate', t, area);
    END LOOP;
END
$$;

-- Cross-table alias uniqueness
DROP TRIGGER IF EXISTS as2_own_key_alias_unique ON as2_own_key;
CREATE TRIGGER as2_own_key_alias_unique
    BEFORE INSERT OR UPDATE OF alias ON as2_own_key
    FOR EACH ROW EXECUTE FUNCTION check_as2_alias_unique();

DROP TRIGGER IF EXISTS as2_partner_certificate_alias_unique ON as2_partner_certificate;
CREATE TRIGGER as2_partner_certificate_alias_unique
    BEFORE INSERT OR UPDATE OF alias ON as2_partner_certificate
    FOR EACH ROW EXECUTE FUNCTION check_as2_alias_unique();


-- =====================================================================================================================
-- Ownership and privileges
-- =====================================================================================================================

-- Every table belongs to the writer role (the msg_metadata id sequence follows its table)
ALTER TABLE msg_metadata            OWNER TO :"admin_role";
ALTER TABLE partner                 OWNER TO :"admin_role";
ALTER TABLE partner_attribute       OWNER TO :"admin_role";
ALTER TABLE partnership             OWNER TO :"admin_role";
ALTER TABLE partnership_attribute   OWNER TO :"admin_role";
ALTER TABLE config_version          OWNER TO :"admin_role";
ALTER TABLE as2_partner_certificate OWNER TO :"admin_role";
ALTER TABLE as2_own_key             OWNER TO :"admin_role";
ALTER TABLE as2_ssl_trusted_ca      OWNER TO :"admin_role";
ALTER FUNCTION bump_config_version()    OWNER TO :"admin_role";
ALTER FUNCTION check_as2_alias_unique() OWNER TO :"admin_role";

-- OpenAS2 only reads the configuration and certificate tables
REVOKE ALL   ON partner, partner_attribute, partnership, partnership_attribute, config_version,
                as2_partner_certificate, as2_own_key, as2_ssl_trusted_ca FROM PUBLIC;
GRANT SELECT ON partner, partner_attribute, partnership, partnership_attribute, config_version,
                as2_partner_certificate, as2_own_key, as2_ssl_trusted_ca TO :"runtime_role";

-- Message tracking: exactly what OpenAS2's DbTrackingModule does (SELECT, INSERT, UPDATE; no DELETE, no DDL)
REVOKE ALL ON msg_metadata FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON msg_metadata        TO :"runtime_role";
GRANT USAGE                  ON SEQUENCE msg_metadata_id_seq TO :"runtime_role";   -- nextval() for the serial id

COMMIT;

-- Done. Next: load partners/partnerships, own keys (encrypted with AS2_KEY_ENCRYPTION_PASSWORD) and partner
-- certificates as as2_config_admin, then start the OpenAS2 container with AS2_DB_USER=openas2.
