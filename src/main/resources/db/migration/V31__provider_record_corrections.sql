-- Acceptance and content-change priority survive projection wipes. Later exact copies inherit
-- priority so replay cannot move an unchanged neighbor ahead of a corrected interval.
ALTER TABLE ingestion_records
    ADD COLUMN google_step_projection_accepted BOOLEAN,
    ADD COLUMN google_step_allocation_priority_record_id INTEGER;

WITH history AS (
    SELECT record.id, batch.source_instance_id, record.provider_record_id,
           record.normalized_record_json,
           lag(record.normalized_record_json) OVER (
               PARTITION BY batch.source_instance_id, record.provider_record_id ORDER BY record.id
           ) AS previous_payload
    FROM ingestion_records record
    JOIN ingestion_batches batch ON batch.id = record.batch_id
    JOIN source_instances instance ON instance.id = batch.source_instance_id
    JOIN sources source ON source.id = instance.source_id
    WHERE source.code = 'google_health'
      AND record.record_type = 'step_interval'
      AND record.provider_record_id IS NOT NULL
      AND batch.status = 'processed'
), decisions AS (
    SELECT id, source_instance_id, provider_record_id,
           max(CASE WHEN previous_payload IS DISTINCT FROM normalized_record_json THEN id END) OVER (
               PARTITION BY source_instance_id, provider_record_id ORDER BY id ROWS UNBOUNDED PRECEDING
           ) AS priority
    FROM history
)
UPDATE ingestion_records record
SET google_step_projection_accepted = EXISTS (
        SELECT 1 FROM step_samples sample
        WHERE sample.source_instance_id = decisions.source_instance_id
          AND sample.provider_record_id = decisions.provider_record_id
    ),
    google_step_allocation_priority_record_id = decisions.priority
FROM decisions
WHERE record.id = decisions.id;

CREATE INDEX ingestion_records_provider_version_idx
    ON ingestion_records (provider_record_id, record_type, id DESC)
    WHERE provider_record_id IS NOT NULL;
