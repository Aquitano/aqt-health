-- ingestion_batches.normalized_payload_json only ever duplicated the per-record
-- ingestion_records.normalized_record_json, and its single reader (the admin batch detail
-- response) already loads those records. The record rows stay the source of truth.

ALTER TABLE ingestion_batches
    DROP COLUMN normalized_payload_json;
