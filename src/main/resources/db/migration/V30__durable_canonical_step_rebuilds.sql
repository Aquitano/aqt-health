ALTER TABLE pending_derived_rebuilds ADD COLUMN revision TEXT NOT NULL DEFAULT '';

-- Daily canonical reads now aggregate the same contributions as day and dashboard reads.
DROP VIEW canonical_step_daily_summaries;

-- Repair every raw step date, including rows whose original derived rebuild never ran.
INSERT INTO pending_derived_rebuilds
    (source_instance_id, derived_kind, affected_date, attempts, next_attempt_at, created_at, updated_at, revision)
SELECT DISTINCT s.source_instance_id, 'STEP_SUMMARY',
       (s.start_at AT TIME ZONE 'UTC')::date + days.day_offset,
       0, now(), now(), now(), 'allocation-v2'
FROM step_samples s
CROSS JOIN LATERAL generate_series(
    0,
    ((s.end_at - INTERVAL '1 microsecond') AT TIME ZONE 'UTC')::date
        - (s.start_at AT TIME ZONE 'UTC')::date
) AS days(day_offset)
ON CONFLICT (source_instance_id, derived_kind, affected_date)
DO UPDATE SET revision = 'allocation-v2', next_attempt_at = now();
