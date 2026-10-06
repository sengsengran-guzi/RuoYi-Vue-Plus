-- Confirmed parent 745 and leaf 975/980 (2026-10-03 probe / 2026-10-06 sanitized JSON).
-- Search cards may omit lineage: keep the two verified leaves to recheck cards locally without trusting gateway filtering.
-- Never enable root 1328; keep unknown categories closed. Preserve all other operator filter settings.
UPDATE sys_config
SET config_value = JSON_SET(config_value, '$.categoryWhitelist', JSON_ARRAY('745','975','980')), update_time = NOW()
WHERE tenant_id='1001' AND config_key='gz.jp.snap.filter';
