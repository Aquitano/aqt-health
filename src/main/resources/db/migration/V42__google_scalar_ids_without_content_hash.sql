-- Google scalar records without a Google-assigned name used to be identified by their time plus a
-- SHA-256 of the whole data point ("heart-rate:<time>:none:<hash>"). Reconciled reads key them by
-- time alone, so a re-sync of a window stored under the old ids would insert every sample again.
-- Dropping the hash suffix makes old and new ids equal. Steps keep their ids: the Google overlap
-- guard already skips re-read intervals, and their acceptance bookkeeping (V31) keys on the id.
WITH google_sources AS (SELECT si.id
                        FROM source_instances si
                                 JOIN sources src ON src.id = si.source_id
                        WHERE src.code = 'google_health'),
     candidates AS (SELECT s.id,
                           regexp_replace(s.provider_record_id, ':[0-9a-f]{64}$', '') AS stable_id,
                           row_number() OVER (
                               PARTITION BY s.source_instance_id,
                                   regexp_replace(s.provider_record_id, ':[0-9a-f]{64}$', ''),
                                   s.metric_type, COALESCE(s.context, ''), COALESCE(s.segment, '')
                               ORDER BY s.id DESC) AS newest_first
                    FROM scalar_samples s
                    WHERE s.source_instance_id IN (SELECT id FROM google_sources)
                      AND s.provider_record_id ~ ':[0-9a-f]{64}$')
UPDATE scalar_samples s
SET provider_record_id = c.stable_id
FROM candidates c
WHERE s.id = c.id
  AND c.newest_first = 1
  AND NOT EXISTS (SELECT 1
                  FROM scalar_samples existing
                  WHERE existing.source_instance_id = s.source_instance_id
                    AND existing.provider_record_id = c.stable_id
                    AND existing.metric_type = s.metric_type
                    AND COALESCE(existing.context, '') = COALESCE(s.context, '')
                    AND COALESCE(existing.segment, '') = COALESCE(s.segment, ''));

-- The log carries the same ids, so replay rebuilds rows under the new ids too. A batch can hold
-- two points for one instant (two devices); those keep their old ids, since ids are unique per batch.
WITH stripped AS (SELECT r.id,
                         r.batch_id,
                         regexp_replace(r.provider_record_id, ':[0-9a-f]{64}$', '') AS stable_id
                  FROM ingestion_records r
                           JOIN ingestion_batches b ON b.id = r.batch_id
                           JOIN source_instances si ON si.id = b.source_instance_id
                           JOIN sources src ON src.id = si.source_id
                  WHERE src.code = 'google_health'
                    AND r.record_type = 'scalar'
                    AND r.provider_record_id ~ ':[0-9a-f]{64}$'),
     unique_in_batch AS (SELECT id, batch_id, stable_id
                         FROM (SELECT *, count(*) OVER (PARTITION BY batch_id, stable_id) AS records FROM stripped) counted
                         WHERE records = 1)
UPDATE ingestion_records r
SET provider_record_id     = u.stable_id,
    normalized_record_json = jsonb_set(r.normalized_record_json, '{providerRecordId}', to_jsonb(u.stable_id))
FROM unique_in_batch u
WHERE r.id = u.id
  AND NOT EXISTS (SELECT 1
                  FROM ingestion_records existing
                  WHERE existing.batch_id = u.batch_id
                    AND existing.provider_record_id = u.stable_id);
