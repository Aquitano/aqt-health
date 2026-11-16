-- Schedules keep the data types they were created with. As in V35, only schedules that still
-- cover the whole previous Google default set gain the new types; a hand-picked subset stays.
UPDATE provider_scheduled_sync_configs
SET data_types = data_types ||
                 ',daily-resting-heart-rate,daily-heart-rate-variability,daily-oxygen-saturation' ||
                 ',daily-respiratory-rate,daily-sleep-temperature-derivations,activity'
WHERE provider_code IN ('google-health', 'google_health')
  AND string_to_array(replace(data_types, ' ', ''), ',') @> ARRAY ['steps', 'sleep', 'heart-rate', 'weight', 'body-fat',
      'heart-rate-variability', 'respiratory-rate-sleep-summary']
  AND NOT string_to_array(replace(data_types, ' ', ''), ',') && ARRAY ['daily-resting-heart-rate', 'activity'];
