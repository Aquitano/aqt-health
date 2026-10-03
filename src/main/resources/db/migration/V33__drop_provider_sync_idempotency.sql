-- The synchronous POST /api/v2/providers/{code}/sync endpoint was removed; sync jobs keep
-- their own idempotency keys on provider_sync_jobs.

DROP TABLE provider_sync_idempotency;
