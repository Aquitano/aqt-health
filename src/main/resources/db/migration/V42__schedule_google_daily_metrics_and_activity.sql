-- Schedules keep the data types they were created with. As in V35, only schedules that still
-- cover the whole previous Google default set gain the new types; a hand-picked subset stays.
-- Each schedule gains only the new types it lacks, so one that already lists some keeps them once.
UPDATE provider_scheduled_sync_configs config
SET data_types = config.data_types || missing.data_types
FROM (SELECT schedule.id, string_agg(',' || new_type, '' ORDER BY position) AS data_types
      FROM provider_scheduled_sync_configs schedule
               CROSS JOIN unnest(ARRAY ['daily-resting-heart-rate', 'daily-heart-rate-variability',
                   'daily-oxygen-saturation', 'daily-respiratory-rate', 'daily-sleep-temperature-derivations',
                   'activity']) WITH ORDINALITY AS new_types(new_type, position)
      WHERE schedule.provider_code IN ('google-health', 'google_health')
        AND string_to_array(replace(schedule.data_types, ' ', ''), ',') @> ARRAY ['steps', 'sleep', 'heart-rate', 'weight',
          'body-fat', 'heart-rate-variability', 'respiratory-rate-sleep-summary']
        AND NOT string_to_array(replace(schedule.data_types, ' ', ''), ',') @> ARRAY [new_type]
      GROUP BY schedule.id) missing
WHERE config.id = missing.id;
