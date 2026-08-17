-- The wire model now has exactly one point-in-time scalar record type ('scalar'); the six
-- legacy per-family types are gone. Convert the stored ingestion records in place so the
-- append-only log stays replayable, then tighten the record_type CHECK.

UPDATE ingestion_records
SET record_type            = 'scalar',
    normalized_record_json = jsonb_strip_nulls(jsonb_build_object(
        'type', 'scalar',
        'providerRecordId', normalized_record_json -> 'providerRecordId',
        'measuredAt', normalized_record_json -> 'measuredAt',
        'metricType', 'heart_rate',
        'value', (normalized_record_json ->> 'bpm')::double precision,
        'unit', 'bpm',
        'context', normalized_record_json -> 'context'))
WHERE record_type = 'heart_rate';

UPDATE ingestion_records
SET record_type            = 'scalar',
    normalized_record_json = jsonb_strip_nulls(jsonb_build_object(
        'type', 'scalar',
        'providerRecordId', normalized_record_json -> 'providerRecordId',
        'measuredAt', normalized_record_json -> 'measuredAt',
        'metricType', 'respiratory_rate',
        'value', (normalized_record_json ->> 'breathsPerMinute')::double precision,
        'unit', 'breaths_per_minute',
        'context', normalized_record_json -> 'context'))
WHERE record_type = 'respiratory_rate';

-- Legacy hrv records carry the unprefixed metric type ('rmssd'); scalar metric types are
-- family-prefixed ('hrv_rmssd').
UPDATE ingestion_records
SET record_type            = 'scalar',
    normalized_record_json = jsonb_strip_nulls(jsonb_build_object(
        'type', 'scalar',
        'providerRecordId', normalized_record_json -> 'providerRecordId',
        'measuredAt', normalized_record_json -> 'measuredAt',
        'metricType', 'hrv_' || (normalized_record_json ->> 'metricType'),
        'value', normalized_record_json -> 'value',
        'unit', normalized_record_json -> 'unit',
        'context', normalized_record_json -> 'context'))
WHERE record_type = 'hrv';

UPDATE ingestion_records
SET record_type            = 'scalar',
    normalized_record_json = jsonb_strip_nulls(jsonb_build_object(
        'type', 'scalar',
        'providerRecordId', normalized_record_json -> 'providerRecordId',
        'measuredAt', normalized_record_json -> 'measuredAt',
        'metricType', normalized_record_json -> 'metricType',
        'value', normalized_record_json -> 'value',
        'unit', normalized_record_json -> 'unit'))
WHERE record_type = 'cardiovascular';

UPDATE ingestion_records
SET record_type            = 'scalar',
    normalized_record_json = jsonb_strip_nulls(jsonb_build_object(
        'type', 'scalar',
        'providerRecordId', normalized_record_json -> 'providerRecordId',
        'measuredAt', normalized_record_json -> 'measuredAt',
        'metricType', normalized_record_json -> 'metricType',
        'value', normalized_record_json -> 'value',
        'unit', normalized_record_json -> 'unit',
        'segment', normalized_record_json -> 'segment'))
WHERE record_type = 'extended_body_measurement';

-- body_measurement was the one multi-value record type: it fans out into one scalar record
-- per populated metric. The scalar_samples rows it produced are repointed at their new
-- record and given the per-metric provider record id the normalizers now emit, so a re-sync
-- deduplicates against them instead of inserting a second copy.
DO
$$
    DECLARE
        legacy                 RECORD;
        metric                 RECORD;
        new_record_id          INTEGER;
        new_provider_record_id TEXT;
    BEGIN
        FOR legacy IN
            SELECT id,
                   batch_id,
                   provider_record_id,
                   normalized_record_json AS json,
                   record_start_at,
                   record_end_at,
                   created_at
            FROM ingestion_records
            WHERE record_type = 'body_measurement'
            LOOP
                FOR metric IN
                    SELECT *
                    FROM (VALUES ('weight', 'kg', legacy.json -> 'weightKg'),
                                 ('body_fat', 'percent', legacy.json -> 'bodyFatPercent'),
                                 ('muscle', 'kg', legacy.json -> 'muscleKg'),
                                 ('water', 'percent', COALESCE(legacy.json -> 'bodyWaterPercent',
                                                               legacy.json -> 'waterPercent')),
                                 ('visceral_fat', 'rating', legacy.json -> 'visceralFatRating'))
                             AS m(metric_type, unit, value)
                    WHERE jsonb_typeof(m.value) = 'number'
                    LOOP
                        new_provider_record_id := CASE
                                                      WHEN legacy.provider_record_id IS NULL THEN NULL
                                                      WHEN legacy.provider_record_id LIKE 'withings:measure:%:body'
                                                          THEN regexp_replace(legacy.provider_record_id, ':body$',
                                                                              ':' || metric.metric_type)
                                                      ELSE legacy.provider_record_id || ':' || metric.metric_type
                            END;

                        INSERT INTO ingestion_records (batch_id, record_type, provider_record_id,
                                                       normalized_record_json, record_start_at,
                                                       record_end_at, created_at)
                        VALUES (legacy.batch_id,
                                'scalar',
                                new_provider_record_id,
                                jsonb_strip_nulls(jsonb_build_object(
                                        'type', 'scalar',
                                        'providerRecordId', to_jsonb(new_provider_record_id),
                                        'measuredAt', legacy.json -> 'measuredAt',
                                        'metricType', metric.metric_type,
                                        'value', metric.value,
                                        'unit', metric.unit)),
                                legacy.record_start_at,
                                legacy.record_end_at,
                                legacy.created_at)
                        RETURNING id INTO new_record_id;

                        UPDATE scalar_samples
                        SET ingestion_record_id = new_record_id,
                            provider_record_id  = new_provider_record_id
                        WHERE ingestion_record_id = legacy.id
                          AND metric_type = metric.metric_type;
                    END LOOP;

                DELETE FROM ingestion_records WHERE id = legacy.id;
            END LOOP;
    END
$$;

ALTER TABLE ingestion_records
    DROP CONSTRAINT ingestion_records_record_type_check;

ALTER TABLE ingestion_records
    ADD CONSTRAINT ingestion_records_record_type_check
        CHECK (record_type IN (
            'step_interval',
            'sleep_session',
            'activity_summary',
            'sleep_summary',
            'blood_pressure',
            'scalar'
        ));
