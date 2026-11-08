-- V2: change counters used by OpenAS2's version_query change detection.
-- Any committed change to the partnership tables (or, from V3, the partner certificate table) increments the
-- counter for its area once per statement. OpenAS2 polls the counter and reloads only when it changes.
--   psql -v ON_ERROR_STOP=1 -d openas2 -f V2__config_version.sql
-- Idempotent.

BEGIN;

CREATE TABLE IF NOT EXISTS config_version
(
    area       varchar(50) PRIMARY KEY,
    version    bigint      NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE config_version OWNER TO as2_config_admin;

INSERT INTO config_version (area) VALUES ('partnerships'), ('certificates')
ON CONFLICT (area) DO NOTHING;

-- SECURITY DEFINER so any role allowed to write the config tables (e.g. a future backend role) bumps the
-- counter without needing UPDATE on config_version itself.
CREATE OR REPLACE FUNCTION bump_config_version() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS
$$
BEGIN
    UPDATE config_version SET version = version + 1, updated_at = now() WHERE area = TG_ARGV[0];
    RETURN NULL;
END
$$;
ALTER FUNCTION bump_config_version() OWNER TO as2_config_admin;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['partner', 'partner_attribute', 'partnership', 'partnership_attribute']
    LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS %I ON %I', t || '_bump_version', t);
        EXECUTE format('CREATE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON %I '
                       'FOR EACH STATEMENT EXECUTE FUNCTION bump_config_version(%L)',
                       t || '_bump_version', t, 'partnerships');
        EXECUTE format('DROP TRIGGER IF EXISTS %I ON %I', t || '_bump_version_truncate', t);
        EXECUTE format('CREATE TRIGGER %I AFTER TRUNCATE ON %I '
                       'FOR EACH STATEMENT EXECUTE FUNCTION bump_config_version(%L)',
                       t || '_bump_version_truncate', t, 'partnerships');
    END LOOP;
END
$$;

REVOKE ALL ON config_version FROM PUBLIC;
GRANT SELECT ON config_version TO openas2;

COMMIT;
