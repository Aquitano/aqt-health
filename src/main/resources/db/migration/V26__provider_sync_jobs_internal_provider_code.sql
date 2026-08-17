-- provider_sync_jobs used to store the hyphenated wire provider code while scheduled_syncs and
-- provider_sync_runs stored the underscored internal one, so Google rows never correlated across
-- the three tables. Fold existing rows onto the internal spelling that the service now writes.
-- Only one spelling was ever written per version, so the partial unique index on
-- (provider_code, idempotency_key) cannot collide.

UPDATE provider_sync_jobs
SET provider_code = REPLACE(LOWER(provider_code), '-', '_')
WHERE provider_code <> REPLACE(LOWER(provider_code), '-', '_');
