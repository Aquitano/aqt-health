-- The wire model now has exactly one point-in-time scalar record type ('scalar'); the six
-- legacy per-family types are gone. Convert the stored ingestion records in place so the
-- append-only log stays replayable, then tighten the record_type CHECK.

-- The standalone heart rate of a Withings measure group now uses the same
-- withings:measure:$grpid:<metric_type> id as every other measure in the group, so rename
-- the stored ids before the conversion; otherwise a re-sync would insert a second sample
-- next to every historical one instead of deduplicating against it.
UPDATE ingestion_records
SET provider_record_id     = regexp_replace(provider_record_id, ':heart-pulse$', ':heart_rate'),
    normalized_record_json = jsonb_set(
        normalized_record_json,
        '{providerRecordId}',
        to_jsonb(regexp_replace(provider_record_id, ':heart-pulse$', ':heart_rate')))
WHERE record_type = 'heart_rate'
  AND provider_record_id LIKE 'withings:measure:%:heart-pulse';

UPDATE scalar_samples
SET provider_record_id = regexp_replace(provider_record_id, ':heart-pulse$', ':heart_rate')
WHERE metric_type = 'heart_rate'
  AND provider_record_id LIKE 'withings:measure:%:heart-pulse';

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
-- One row per (legacy record, populated metric), carrying the provider record id the
-- normalizers now emit for it. A Withings measure group always takes the per-metric id.
-- Any other record keeps its id when it holds a single metric, which is exactly what a
-- single-metric producer (Google weight and body fat) still emits; only a genuine fan-out
-- needs the suffix, to keep its records distinct.
CREATE TEMP TABLE legacy_body_expansion AS
SELECT r.id                              AS legacy_record_id,
       r.batch_id,
       r.normalized_record_json          AS json,
       r.record_start_at,
       r.record_end_at,
       r.created_at,
       m.metric_type,
       m.unit,
       m.value,
       count(*) OVER (PARTITION BY r.id) AS metric_count,
       CASE
           WHEN r.provider_record_id IS NULL THEN NULL
           WHEN r.provider_record_id LIKE 'withings:measure:%:body'
               THEN regexp_replace(r.provider_record_id, ':body$', ':' || m.metric_type)
           WHEN count(*) OVER (PARTITION BY r.id) > 1
               THEN r.provider_record_id || ':' || m.metric_type
           ELSE r.provider_record_id
           END                           AS new_provider_record_id
FROM ingestion_records r
         CROSS JOIN LATERAL (
    VALUES ('weight', 'kg', r.normalized_record_json -> 'weightKg'),
           ('body_fat', 'percent', r.normalized_record_json -> 'bodyFatPercent'),
           ('muscle', 'kg', r.normalized_record_json -> 'muscleKg'),
           ('water', 'percent', COALESCE(r.normalized_record_json -> 'bodyWaterPercent',
                                         r.normalized_record_json -> 'waterPercent')),
           ('visceral_fat', 'rating', r.normalized_record_json -> 'visceralFatRating')
    ) AS m(metric_type, unit, value)
WHERE r.record_type = 'body_measurement'
  AND jsonb_typeof(m.value) = 'number';

-- A single-metric record does not fan out, so it converts in place like the other legacy
-- types and its samples keep pointing at it.
UPDATE ingestion_records r
SET record_type            = 'scalar',
    provider_record_id     = e.new_provider_record_id,
    normalized_record_json = jsonb_strip_nulls(jsonb_build_object(
        'type', 'scalar',
        'providerRecordId', to_jsonb(e.new_provider_record_id),
        'measuredAt', e.json -> 'measuredAt',
        'metricType', e.metric_type,
        'value', e.value,
        'unit', e.unit))
FROM legacy_body_expansion e
WHERE e.legacy_record_id = r.id
  AND e.metric_count = 1;

UPDATE scalar_samples s
SET provider_record_id = e.new_provider_record_id
FROM legacy_body_expansion e
WHERE e.metric_count = 1
  AND s.ingestion_record_id = e.legacy_record_id
  AND s.metric_type = e.metric_type;

DO
$$
    DECLARE
        expansion     RECORD;
        new_record_id INTEGER;
    BEGIN
        FOR expansion IN
            SELECT *
            FROM legacy_body_expansion
            WHERE metric_count > 1
            ORDER BY legacy_record_id, metric_type
            LOOP
                INSERT INTO ingestion_records (batch_id, record_type, provider_record_id,
                                               normalized_record_json, record_start_at,
                                               record_end_at, created_at)
                VALUES (expansion.batch_id,
                        'scalar',
                        expansion.new_provider_record_id,
                        jsonb_strip_nulls(jsonb_build_object(
                                'type', 'scalar',
                                'providerRecordId', to_jsonb(expansion.new_provider_record_id),
                                'measuredAt', expansion.json -> 'measuredAt',
                                'metricType', expansion.metric_type,
                                'value', expansion.value,
                                'unit', expansion.unit)),
                        expansion.record_start_at,
                        expansion.record_end_at,
                        expansion.created_at)
                RETURNING id INTO new_record_id;

                UPDATE scalar_samples
                SET ingestion_record_id = new_record_id,
                    provider_record_id  = expansion.new_provider_record_id
                WHERE ingestion_record_id = expansion.legacy_record_id
                  AND metric_type = expansion.metric_type;
            END LOOP;
    END
$$;

-- Leaves the fanned-out originals and any record that held no usable metric.
DELETE
FROM ingestion_records
WHERE record_type = 'body_measurement';

DROP TABLE legacy_body_expansion;

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
