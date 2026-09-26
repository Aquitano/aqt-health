ALTER TABLE pending_derived_rebuilds ADD COLUMN revision TEXT NOT NULL DEFAULT '';

-- Daily canonical reads now aggregate the same contributions as day and dashboard reads.
DROP VIEW canonical_step_daily_summaries;

-- Recompute existing contributions with cumulative rounding, including midnight splits.
INSERT INTO pending_derived_rebuilds
    (source_instance_id, derived_kind, affected_date, attempts, next_attempt_at, created_at, updated_at, revision)
SELECT DISTINCT source_instance_id, 'STEP_SUMMARY', date, 0, now(), now(), now(), 'allocation-v2'
FROM canonical_step_samples
ON CONFLICT (source_instance_id, derived_kind, affected_date)
DO UPDATE SET revision = 'allocation-v2', next_attempt_at = now();
