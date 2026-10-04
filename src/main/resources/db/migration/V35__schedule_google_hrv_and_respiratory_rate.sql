-- Schedules keep the data types they were created with, so ones made before these Google types
-- existed would never sync them. Only schedules that still cover the whole previous default set
-- get them; a hand-picked subset stays as chosen.
UPDATE provider_scheduled_sync_configs
SET data_types = data_types || ',heart-rate-variability,respiratory-rate-sleep-summary'
WHERE provider_code IN ('google-health', 'google_health')
  AND string_to_array(replace(data_types, ' ', ''), ',') @> ARRAY ['steps', 'sleep', 'heart-rate', 'weight', 'body-fat']
  AND NOT string_to_array(replace(data_types, ' ', ''), ',') && ARRAY ['heart-rate-variability', 'respiratory-rate-sleep-summary'];
