-- Google scalar records without a Google-assigned name used to be identified by their time plus a
-- SHA-256 of the whole data point ("heart-rate:<time>:none:<hash>"). Reconciled reads key them by
-- time alone, so a re-sync of a window stored under the old ids would insert every sample again.
-- Dropping the hash suffix makes old and new ids equal. Steps keep their ids: the Google overlap
-- guard already skips re-read intervals, and their acceptance bookkeeping (V31) keys on the id.
--
-- A row and its log record must keep matching ids, or replay could not pair them. The log records
-- decide: an id is stripped from every record of the account unless some batch holds two records
-- that would share it (two devices at one instant). Deciding per account, not per batch, keeps a
-- reading that a later batch resent from ending up under both ids. Rows go first, while the log
-- still holds the old ids.
CREATE TEMP TABLE google_scalar_id_renames AS
SELECT r.id                                                         AS record_id,
       r.batch_id,
       b.source_instance_id,
       regexp_replace(r.provider_record_id, ':[0-9a-f]{64}$', '') AS stable_id
FROM ingestion_records r
         JOIN ingestion_batches b ON b.id = r.batch_id
         JOIN source_instances si ON si.id = b.source_instance_id
         JOIN sources src ON src.id = si.source_id
WHERE src.code = 'google_health'
  AND r.record_type = 'scalar'
  AND r.provider_record_id ~ ':[0-9a-f]{64}$';

DELETE
FROM google_scalar_id_renames renames
    USING (SELECT source_instance_id, stable_id
           FROM google_scalar_id_renames
           GROUP BY batch_id, source_instance_id, stable_id
           HAVING count(*) > 1
           UNION
           SELECT candidates.source_instance_id, candidates.stable_id
           FROM google_scalar_id_renames candidates
           WHERE EXISTS (SELECT 1
                         FROM ingestion_records existing
                         WHERE existing.batch_id = candidates.batch_id
                           AND existing.provider_record_id = candidates.stable_id)) collided
WHERE renames.source_instance_id = collided.source_instance_id
  AND renames.stable_id = collided.stable_id;

CREATE INDEX ON google_scalar_id_renames (record_id);
ANALYZE google_scalar_id_renames;

-- Several syncs of one window each stored a row for the same reading; only the newest takes the
-- id. The older rows' log records are superseded by the newest, so replay skips them.
WITH rows_to_rename AS (SELECT s.id,
                               renames.stable_id,
                               row_number() OVER (
                                   PARTITION BY s.source_instance_id, renames.stable_id, s.metric_type,
                                       COALESCE(s.context, ''), COALESCE(s.segment, '')
                                   ORDER BY s.id DESC) AS newest_first
                        FROM scalar_samples s
                                 JOIN google_scalar_id_renames renames ON renames.record_id = s.ingestion_record_id
                        WHERE s.provider_record_id ~ ':[0-9a-f]{64}$')
UPDATE scalar_samples s
SET provider_record_id = rows_to_rename.stable_id
FROM rows_to_rename
WHERE s.id = rows_to_rename.id
  AND rows_to_rename.newest_first = 1
  AND NOT EXISTS (SELECT 1
                  FROM scalar_samples existing
                  WHERE existing.source_instance_id = s.source_instance_id
                    AND existing.provider_record_id = rows_to_rename.stable_id
                    AND existing.metric_type = s.metric_type
                    AND COALESCE(existing.context, '') = COALESCE(s.context, '')
                    AND COALESCE(existing.segment, '') = COALESCE(s.segment, ''));

UPDATE ingestion_records r
SET provider_record_id     = renames.stable_id,
    normalized_record_json = jsonb_set(r.normalized_record_json, '{providerRecordId}', to_jsonb(renames.stable_id))
FROM google_scalar_id_renames renames
WHERE r.id = renames.record_id;

DROP TABLE google_scalar_id_renames;
