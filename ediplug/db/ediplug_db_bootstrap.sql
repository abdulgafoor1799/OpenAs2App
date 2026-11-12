-- =====================================================================================================================
-- EDIPLUG database bootstrap (AWS RDS PostgreSQL 16+; tested on 18.3)
--
-- Creates, for one environment (dev, staging, prod ...):
--   <app_db>   e.g. ediplug-dev    EdiPlug orchestrator database, owned by <app_role>; all application tables
--                                  (users, sessions, clients, partners, documents ...) + alembic_version = '0001'
--   <as2_db>   e.g. openas2-dev    OpenAS2 configuration + message tracking database; every table OpenAS2 4.15 needs
--   roles      <app_role>          orchestrator login, owns <app_db>
--              <admin_role>        owns all <as2_db> tables; single writer of the AS2 configuration (orchestrator)
--              <runtime_role>      OpenAS2 container login: SELECT on config/cert tables, SELECT/INSERT/UPDATE on
--                                  msg_metadata, owns nothing
--
-- Run as a login with CREATEDB + CREATEROLE (RDS master user, or e.g. admin_user), connected to database "postgres":
--
--   psql "host=<rds> port=5432 user=<admin> dbname=postgres sslmode=verify-full sslrootcert=ap-south-1-bundle.pem" \
--        -f ediplug_db_bootstrap.sql
--
-- Values not given are asked for. When the environment name itself was given (-v env / EDIPLUG_ENV), names not given
-- default silently (add -v ask=1 to be asked anyway); missing passwords are always asked for.
-- Ways to give a value (first match wins):
--   1. psql variables:  -v env=dev -v app_db=ediplug-dev -v app_role=... -v as2_db=... -v runtime_role=...
--                       -v admin_role=... -v app_password=... -v runtime_password=... -v admin_password=...
--   2. environment:     EDIPLUG_ENV, EDIPLUG_APP_DB, EDIPLUG_APP_ROLE, EDIPLUG_APP_PASSWORD, OPENAS2_DB,
--                       OPENAS2_RUNTIME_ROLE, OPENAS2_RUNTIME_PASSWORD, OPENAS2_ADMIN_ROLE, OPENAS2_ADMIN_PASSWORD
--   3. prompt:          press Enter to accept the default in [brackets]. psql shows what you type, so give
--                       passwords through the environment when others can see your screen.
--
-- Idempotent: existing databases, roles and tables are kept (passwords are only used when a role is created).
-- Afterwards: the orchestrator uses <app_db> and <as2_db> (its `alembic upgrade head` is then a no-op); the OpenAS2
-- container uses AS2_DB_NAME=<as2_db>, AS2_DB_USER=<runtime_role>.
-- Requires psql 15+ (\getenv). The OpenAS2 part is identical to openas2_rds_init.sql.
-- =====================================================================================================================

\set ON_ERROR_STOP 1
\set QUIET 1

-- ---------------------------------------------------------------------------------------------------------------------
-- Inputs: psql variable -> environment variable -> prompt -> default derived from the environment name
-- ---------------------------------------------------------------------------------------------------------------------
\if :{?env}
\else
\getenv env EDIPLUG_ENV
\endif
\if :{?env}
\else
\prompt 'Environment name (e.g. dev, staging, prod): ' env
-- Typed in: also ask for the names (Enter = default)
\set ask 1
\endif
SELECT lower(trim(:'env')) AS env \gset
SELECT :'env' ~ '^[a-z][a-z0-9_]{0,20}$' AS env_ok \gset
\if :env_ok
\else
\echo 'Environment name must start with a letter and contain only a-z, 0-9 or _ (max 21 characters).'
\quit
\endif

\if :{?app_db}
\else
\getenv app_db EDIPLUG_APP_DB
\endif
\if :{?app_db}
\elif :{?ask}
\prompt 'EdiPlug application database [ediplug-':env']: ' app_db
\else
\set app_db ''
\endif
\if :{?app_role}
\else
\getenv app_role EDIPLUG_APP_ROLE
\endif
\if :{?app_role}
\elif :{?ask}
\prompt 'EdiPlug application role [ediplug_app_':env']: ' app_role
\else
\set app_role ''
\endif
\if :{?as2_db}
\else
\getenv as2_db OPENAS2_DB
\endif
\if :{?as2_db}
\elif :{?ask}
\prompt 'OpenAS2 database [openas2-':env']: ' as2_db
\else
\set as2_db ''
\endif
\if :{?runtime_role}
\else
\getenv runtime_role OPENAS2_RUNTIME_ROLE
\endif
\if :{?runtime_role}
\elif :{?ask}
\prompt 'OpenAS2 runtime role, used by the OpenAS2 container [openas2_':env']: ' runtime_role
\else
\set runtime_role ''
\endif
\if :{?admin_role}
\else
\getenv admin_role OPENAS2_ADMIN_ROLE
\endif
\if :{?admin_role}
\elif :{?ask}
\prompt 'OpenAS2 writer role, used by the orchestrator [as2_config_admin_':env']: ' admin_role
\else
\set admin_role ''
\endif

SELECT coalesce(nullif(trim(:'app_db'), ''), 'ediplug-' || :'env')              AS app_db,
       coalesce(nullif(trim(:'app_role'), ''), 'ediplug_app_' || :'env')        AS app_role,
       coalesce(nullif(trim(:'as2_db'), ''), 'openas2-' || :'env')              AS as2_db,
       coalesce(nullif(trim(:'runtime_role'), ''), 'openas2_' || :'env')        AS runtime_role,
       coalesce(nullif(trim(:'admin_role'), ''), 'as2_config_admin_' || :'env') AS admin_role \gset

-- Passwords are only needed for roles that do not exist yet
SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_role')     AS need_app_pw,
       NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'runtime_role') AS need_runtime_pw,
       NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'admin_role')   AS need_admin_pw \gset
\if :need_app_pw
\if :{?app_password}
\else
\getenv app_password EDIPLUG_APP_PASSWORD
\endif
\if :{?app_password}
\else
\prompt 'Password for the new role ':app_role': ' app_password
\endif
\else
\set app_password ''
\endif
\if :need_runtime_pw
\if :{?runtime_password}
\else
\getenv runtime_password OPENAS2_RUNTIME_PASSWORD
\endif
\if :{?runtime_password}
\else
\prompt 'Password for the new role ':runtime_role': ' runtime_password
\endif
\else
\set runtime_password ''
\endif
\if :need_admin_pw
\if :{?admin_password}
\else
\getenv admin_password OPENAS2_ADMIN_PASSWORD
\endif
\if :{?admin_password}
\else
\prompt 'Password for the new role ':admin_role': ' admin_password
\endif
\else
\set admin_password ''
\endif

SELECT (:'need_app_pw' AND length(:'app_password') < 12)
       OR (:'need_runtime_pw' AND length(:'runtime_password') < 12)
       OR (:'need_admin_pw' AND length(:'admin_password') < 12)                                         AS weak_password,
       :'app_db' = :'as2_db'                                                                             AS same_db,
       cardinality(ARRAY(SELECT DISTINCT unnest(ARRAY[:'app_role', :'runtime_role', :'admin_role']))) < 3 AS same_role \gset
\if :weak_password
\echo 'Each new role needs a password of at least 12 characters.'
\quit
\endif
\if :same_db
\echo 'The application database and the OpenAS2 database must be different.'
\quit
\endif
\if :same_role
\echo 'The application, OpenAS2 writer and OpenAS2 runtime roles must be three different roles.'
\quit
\endif

\echo
\echo 'Creating / checking:'
\echo '  application database:' :app_db ' owner:' :app_role
\echo '  OpenAS2 database:    ' :as2_db ' writer:' :admin_role ' runtime:' :runtime_role
\echo

-- ---------------------------------------------------------------------------------------------------------------------
-- Cluster level: roles and databases
-- ---------------------------------------------------------------------------------------------------------------------
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_role', :'app_password') WHERE :'need_app_pw' \gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'runtime_role', :'runtime_password') WHERE :'need_runtime_pw' \gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'admin_role', :'admin_password') WHERE :'need_admin_pw' \gexec
\unset app_password
\unset runtime_password
\unset admin_password

-- The RDS master / admin_user are not superusers: membership is needed to create objects owned by these roles
GRANT :"app_role", :"runtime_role", :"admin_role" TO CURRENT_USER;

SELECT format('CREATE DATABASE %I OWNER %I ENCODING %L', :'app_db', :'app_role', 'UTF8')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'app_db') \gexec
SELECT format('CREATE DATABASE %I ENCODING %L', :'as2_db', 'UTF8')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'as2_db') \gexec

REVOKE ALL    ON DATABASE :"app_db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"app_db" TO :"app_role";
REVOKE ALL    ON DATABASE :"as2_db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"as2_db" TO :"runtime_role", :"admin_role";


-- =====================================================================================================================
-- Application database: EdiPlug orchestrator tables (= Alembic revision 0001, the original Prisma migration)
-- =====================================================================================================================
\connect :"app_db"
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO :"app_role";

SELECT to_regclass('public."user"') IS NULL AS need_app_schema \gset
\if :need_app_schema
\echo 'Creating application tables in' :app_db
BEGIN;
-- As the application role, so it owns every table, type and index
SET LOCAL ROLE :"app_role";
-- CreateSchema


-- CreateEnum
CREATE TYPE "UserRole" AS ENUM ('SYSTEM_ADMIN', 'CLIENT_ADMIN', 'CLIENT_USER');

-- CreateEnum
CREATE TYPE "UserStatus" AS ENUM ('ACTIVE', 'INVITED', 'DISABLED');

-- CreateEnum
CREATE TYPE "ClientStatus" AS ENUM ('ONBOARDING', 'ACTIVE', 'INACTIVE', 'SUSPENDED');

-- CreateEnum
CREATE TYPE "PartnerStatus" AS ENUM ('ACTIVE', 'INACTIVE');

-- CreateEnum
CREATE TYPE "MappingStatus" AS ENUM ('ACTIVE', 'INACTIVE', 'PENDING');

-- CreateEnum
CREATE TYPE "DocumentDirection" AS ENUM ('INBOUND', 'OUTBOUND');

-- CreateEnum
CREATE TYPE "TransactionSetType" AS ENUM ('TS_850', 'TS_846', 'TS_855', 'TS_856', 'TS_810', 'TS_820', 'TS_997');

-- CreateEnum
CREATE TYPE "DocumentStatus" AS ENUM ('RECEIVED', 'PARSING', 'PARSED', 'VALIDATION_FAILED', 'READY_TO_SEND', 'SENT', 'ACKNOWLEDGED', 'FAILED');

-- CreateTable
CREATE TABLE "user" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "emailVerified" BOOLEAN NOT NULL DEFAULT false,
    "image" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "user_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "session" (
    "id" TEXT NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "token" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "ipAddress" TEXT,
    "userAgent" TEXT,
    "userId" TEXT NOT NULL,

    CONSTRAINT "session_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "account" (
    "id" TEXT NOT NULL,
    "accountId" TEXT NOT NULL,
    "providerId" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "accessToken" TEXT,
    "refreshToken" TEXT,
    "idToken" TEXT,
    "accessTokenExpiresAt" TIMESTAMP(3),
    "refreshTokenExpiresAt" TIMESTAMP(3),
    "scope" TEXT,
    "password" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "account_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "verification" (
    "id" TEXT NOT NULL,
    "identifier" TEXT NOT NULL,
    "value" TEXT NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "verification_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "user_profile" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "clientId" TEXT,
    "role" "UserRole" NOT NULL,
    "status" "UserStatus" NOT NULL DEFAULT 'ACTIVE',
    "portalAccessScope" JSONB,
    "createdById" TEXT,
    "updatedById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "user_profile_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "clients" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "slug" TEXT NOT NULL,
    "status" "ClientStatus" NOT NULL DEFAULT 'ONBOARDING',
    "contactEmail" TEXT,
    "settings" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "clients_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "partners" (
    "id" TEXT NOT NULL,
    "code" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "ediStandard" TEXT NOT NULL DEFAULT 'X12',
    "status" "PartnerStatus" NOT NULL DEFAULT 'ACTIVE',
    "supportedTransactionSets" "TransactionSetType"[],
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "partners_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "client_partner_mappings" (
    "id" TEXT NOT NULL,
    "clientId" TEXT NOT NULL,
    "partnerId" TEXT NOT NULL,
    "status" "MappingStatus" NOT NULL DEFAULT 'PENDING',
    "clientAs2Config" JSONB,
    "partnerAs2Config" JSONB,
    "settings" JSONB,
    "clientQualifier" TEXT,
    "clientIsaId" TEXT,
    "partnerQualifier" TEXT,
    "partnerIsaId" TEXT,
    "acknowledgmentRequested" TEXT,
    "usageIndicator" TEXT,
    "gsClientCode" TEXT,
    "gsPartnerCode" TEXT,
    "activatedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "client_partner_mappings_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "partner_transaction_configs" (
    "id" TEXT NOT NULL,
    "partnerId" TEXT NOT NULL,
    "transactionSet" "TransactionSetType" NOT NULL,
    "direction" "DocumentDirection",
    "guidePath" TEXT NOT NULL,
    "cdoTemplatePath" TEXT NOT NULL,
    "guideVersion" TEXT NOT NULL,
    "cdoTemplateVersion" TEXT NOT NULL,
    "guideChecksum" TEXT,
    "cdoTemplateChecksum" TEXT,
    "isActive" BOOLEAN NOT NULL DEFAULT true,
    "metadata" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "partner_transaction_configs_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "documents" (
    "id" TEXT NOT NULL,
    "clientId" TEXT NOT NULL,
    "partnerId" TEXT NOT NULL,
    "clientPartnerMappingId" TEXT,
    "transactionSet" "TransactionSetType" NOT NULL,
    "direction" "DocumentDirection" NOT NULL,
    "status" "DocumentStatus" NOT NULL DEFAULT 'RECEIVED',
    "rawEdiPath" TEXT,
    "cdoJson" JSONB,
    "validationReport" JSONB,
    "errorDetails" JSONB,
    "isaControlNumber" TEXT,
    "gsControlNumber" TEXT,
    "stControlNumber" TEXT,
    "idempotencyKey" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "documents_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "edi_control_counters" (
    "id" TEXT NOT NULL,
    "clientPartnerMappingId" TEXT NOT NULL,
    "nextIsaControlNumber" INTEGER NOT NULL DEFAULT 1,
    "nextGsControlNumber" INTEGER NOT NULL DEFAULT 1,
    "nextStControlNumber" INTEGER NOT NULL DEFAULT 1,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "edi_control_counters_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "user_email_key" ON "user"("email");

-- CreateIndex
CREATE UNIQUE INDEX "session_token_key" ON "session"("token");

-- CreateIndex
CREATE INDEX "session_userId_idx" ON "session"("userId");

-- CreateIndex
CREATE INDEX "account_userId_idx" ON "account"("userId");

-- CreateIndex
CREATE UNIQUE INDEX "account_providerId_accountId_key" ON "account"("providerId", "accountId");

-- CreateIndex
CREATE INDEX "verification_identifier_idx" ON "verification"("identifier");

-- CreateIndex
CREATE UNIQUE INDEX "user_profile_userId_key" ON "user_profile"("userId");

-- CreateIndex
CREATE INDEX "user_profile_clientId_idx" ON "user_profile"("clientId");

-- CreateIndex
CREATE INDEX "user_profile_role_status_idx" ON "user_profile"("role", "status");

-- CreateIndex
CREATE UNIQUE INDEX "clients_slug_key" ON "clients"("slug");

-- CreateIndex
CREATE UNIQUE INDEX "partners_code_key" ON "partners"("code");

-- CreateIndex
CREATE INDEX "client_partner_mappings_partnerId_idx" ON "client_partner_mappings"("partnerId");

-- CreateIndex
CREATE UNIQUE INDEX "client_partner_mappings_clientId_partnerId_key" ON "client_partner_mappings"("clientId", "partnerId");

-- CreateIndex
CREATE INDEX "partner_transaction_configs_partnerId_transactionSet_isActi_idx" ON "partner_transaction_configs"("partnerId", "transactionSet", "isActive");

-- CreateIndex
CREATE UNIQUE INDEX "partner_transaction_configs_partnerId_transactionSet_direct_key" ON "partner_transaction_configs"("partnerId", "transactionSet", "direction", "guideVersion", "cdoTemplateVersion");

-- CreateIndex
CREATE UNIQUE INDEX "documents_idempotencyKey_key" ON "documents"("idempotencyKey");

-- CreateIndex
CREATE INDEX "documents_clientId_createdAt_idx" ON "documents"("clientId", "createdAt");

-- CreateIndex
CREATE INDEX "documents_clientId_partnerId_transactionSet_idx" ON "documents"("clientId", "partnerId", "transactionSet");

-- CreateIndex
CREATE INDEX "documents_status_idx" ON "documents"("status");

-- CreateIndex
CREATE UNIQUE INDEX "edi_control_counters_clientPartnerMappingId_key" ON "edi_control_counters"("clientPartnerMappingId");

-- AddForeignKey
ALTER TABLE "session" ADD CONSTRAINT "session_userId_fkey" FOREIGN KEY ("userId") REFERENCES "user"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "account" ADD CONSTRAINT "account_userId_fkey" FOREIGN KEY ("userId") REFERENCES "user"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "user_profile" ADD CONSTRAINT "user_profile_userId_fkey" FOREIGN KEY ("userId") REFERENCES "user"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "user_profile" ADD CONSTRAINT "user_profile_clientId_fkey" FOREIGN KEY ("clientId") REFERENCES "clients"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "client_partner_mappings" ADD CONSTRAINT "client_partner_mappings_clientId_fkey" FOREIGN KEY ("clientId") REFERENCES "clients"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "client_partner_mappings" ADD CONSTRAINT "client_partner_mappings_partnerId_fkey" FOREIGN KEY ("partnerId") REFERENCES "partners"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "partner_transaction_configs" ADD CONSTRAINT "partner_transaction_configs_partnerId_fkey" FOREIGN KEY ("partnerId") REFERENCES "partners"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "documents" ADD CONSTRAINT "documents_clientId_fkey" FOREIGN KEY ("clientId") REFERENCES "clients"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "documents" ADD CONSTRAINT "documents_partnerId_fkey" FOREIGN KEY ("partnerId") REFERENCES "partners"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "documents" ADD CONSTRAINT "documents_clientPartnerMappingId_fkey" FOREIGN KEY ("clientPartnerMappingId") REFERENCES "client_partner_mappings"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "edi_control_counters" ADD CONSTRAINT "edi_control_counters_clientPartnerMappingId_fkey" FOREIGN KEY ("clientPartnerMappingId") REFERENCES "client_partner_mappings"("id") ON DELETE CASCADE ON UPDATE CASCADE;

CREATE TABLE alembic_version (
    version_num VARCHAR(32) NOT NULL,
    CONSTRAINT alembic_version_pkc PRIMARY KEY (version_num)
);
INSERT INTO alembic_version (version_num) VALUES ('0001');
COMMIT;
\else
\echo 'Application tables already exist in' :app_db '- kept'
\endif


-- =====================================================================================================================
-- OpenAS2 database: configuration (partners, partnerships, keys, certificates), change counters, message tracking
-- =====================================================================================================================
\connect :"as2_db"
\echo 'Creating / checking OpenAS2 tables in' :as2_db
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

\echo
\echo 'Done. Connection settings:'
\echo '  orchestrator DATABASE_URL     -> database' :app_db 'user' :app_role
\echo '  orchestrator AS2_DATABASE_URL -> database' :as2_db 'user' :admin_role
\echo '  OpenAS2      AS2_DB_NAME=' :as2_db ' AS2_DB_USER=' :runtime_role
