-- Replay can look up newer arrivals even when the corresponding projection was wiped or moved
-- to another date. The partial index keeps this lookup bounded as the append-only log grows.
CREATE INDEX ingestion_records_provider_version_idx
    ON ingestion_records (provider_record_id, record_type, id DESC)
    WHERE provider_record_id IS NOT NULL;
