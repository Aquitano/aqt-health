ALTER TABLE ingestion_batches
    ADD COLUMN sync_window_key TEXT,
    ADD COLUMN sync_content_hash TEXT,
    ADD CONSTRAINT ingestion_batches_sync_snapshot_pair CHECK (
        (sync_window_key IS NULL) = (sync_content_hash IS NULL)
    );

CREATE INDEX ingestion_batches_latest_sync_snapshot
    ON ingestion_batches (source_instance_id, sync_window_key, status, id DESC)
    WHERE sync_window_key IS NOT NULL;
