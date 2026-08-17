ALTER TABLE provider_sync_jobs
    ADD COLUMN restart_count INTEGER NOT NULL DEFAULT 0;
