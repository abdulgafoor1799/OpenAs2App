-- =====================================================================================================================
-- EDIPLUG: one-off clean-up of partnership attributes written before the admin API's write-time rules existed.
--
-- Run once per environment against the OpenAS2 database, as the writer role (as2_config_admin_<env>):
--
--   psql "host=<rds> port=5432 user=as2_config_admin_<env> dbname=openas2-<env> sslmode=verify-full \
--         sslrootcert=ap-south-1-bundle.pem" -v async_mdn_url=https://as2.ediplug.com/mdn \
--        -f fix_partnership_attributes.sql
--
-- What it changes (all in one transaction; OpenAS2 reloads the partnerships within 30 s via the config_version
-- trigger):
--   1. as2_mdn_options containing $attribute.sign$ gets the literal algorithm: the partnership's sign value, or sha-256
--      when it has none (an unresolvable $attribute.sign$ stops every partnership reload).
--   2. outbound partnerships (as2_url set or outbox poller enabled) without as2_mdn_options get "none" (OpenAS2 fails
--      every send without it).
--   3. blockerrormdn = 'false' is removed (OpenAS2 treats any value, "false" included, as "never send MDNs").
--   4. as2_receipt_option holding a $properties.X$ placeholder gets the literal async MDN URL (async_mdn_url).
-- Idempotent: a second run changes nothing. Review the counts it prints before relying on it in production.
-- =====================================================================================================================
\set ON_ERROR_STOP 1
\if :{?async_mdn_url}
\else
  \echo 'Pass -v async_mdn_url=<the public /mdn URL, AS2_ASYNC_MDN_URL of the orchestrator>'
  \quit
\endif

BEGIN;

\echo '1. $attribute.sign$ in as2_mdn_options'
UPDATE partnership_attribute m
SET attribute_value = replace(m.attribute_value, '$attribute.sign$', COALESCE(
      (SELECT lower(s.attribute_value) FROM partnership_attribute s
       WHERE s.partnership_id = m.partnership_id AND s.category = 'attribute' AND s.attribute_name = 'sign'),
      'sha-256'))
WHERE m.category = 'attribute' AND m.attribute_name = 'as2_mdn_options'
  AND m.attribute_value LIKE '%$attribute.sign$%';

\echo '2. outbound partnerships without as2_mdn_options'
INSERT INTO partnership_attribute (partnership_id, category, attribute_name, attribute_value)
SELECT p.id, 'attribute', 'as2_mdn_options', 'none'
FROM partnership p
WHERE (EXISTS (SELECT 1 FROM partnership_attribute a WHERE a.partnership_id = p.id AND a.category = 'attribute'
                 AND a.attribute_name = 'as2_url')
       OR EXISTS (SELECT 1 FROM partnership_attribute a WHERE a.partnership_id = p.id AND a.category = 'pollerConfig'
                    AND a.attribute_name = 'enabled' AND lower(a.attribute_value) = 'true'))
  AND NOT EXISTS (SELECT 1 FROM partnership_attribute a WHERE a.partnership_id = p.id AND a.category = 'attribute'
                    AND a.attribute_name = 'as2_mdn_options');

\echo '3. blockerrormdn = false'
DELETE FROM partnership_attribute
WHERE category = 'attribute' AND attribute_name = 'blockerrormdn' AND lower(trim(attribute_value)) = 'false';

\echo '4. load-time placeholder in as2_receipt_option'
UPDATE partnership_attribute
SET attribute_value = :'async_mdn_url'
WHERE category = 'attribute' AND attribute_name = 'as2_receipt_option' AND attribute_value LIKE '%$properties.%$%';

\echo 'Remaining load-time placeholders (fix these by hand, then save the partnership through the admin API):'
SELECT p.name AS partnership, a.category, a.attribute_name, a.attribute_value
FROM partnership_attribute a JOIN partnership p ON p.id = a.partnership_id
WHERE a.category IN ('attribute', 'pollerConfig')
  AND a.attribute_value ~ '\$(attribute|properties|partnership)\.[^$]*\$'
ORDER BY 1, 2, 3;

COMMIT;
