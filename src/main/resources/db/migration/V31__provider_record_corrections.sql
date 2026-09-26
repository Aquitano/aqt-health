-- Preserve the original Google overlap decision independently from replayable projections.
-- This metadata is written once; normalized provider payloads remain immutable.
ALTER TABLE ingestion_records ADD COLUMN google_step_projection_accepted BOOLEAN;

UPDATE ingestion_records record
SET google_step_projection_accepted = EXISTS (
    SELECT 1 FROM step_samples sample
    WHERE sample.source_instance_id = batch.source_instance_id
      AND sample.provider_record_id = record.provider_record_id
)
FROM ingestion_batches batch
JOIN source_instances instance ON instance.id = batch.source_instance_id
JOIN sources source ON source.id = instance.source_id
WHERE record.batch_id = batch.id
  AND source.code = 'google_health'
  AND record.record_type = 'step_interval'
  AND record.provider_record_id IS NOT NULL
  AND batch.status = 'processed';

CREATE INDEX ingestion_records_provider_version_idx
    ON ingestion_records (provider_record_id, record_type, id DESC)
    WHERE provider_record_id IS NOT NULL;
