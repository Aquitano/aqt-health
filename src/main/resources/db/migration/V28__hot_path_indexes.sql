-- Indexes for filters that measurement showed running as sequential scans.

-- Replay plans and per-day replay reads filter ingestion_records on (record_type, record_start_at);
-- a two-year replay did ~730 full scans of the table without this.
CREATE INDEX ingestion_records_record_start_at_idx
    ON ingestion_records (record_type, record_start_at);

-- Canonical step reads join back on step_sample_id and filter on algorithm_version, then scan a
-- date/start_at window. Together these two take the read from 21.3 ms to 0.38 ms.
CREATE INDEX canonical_step_samples_sample_algo_date_idx
    ON canonical_step_samples (step_sample_id, algorithm_version, date);

CREATE INDEX canonical_step_samples_algo_start_idx
    ON canonical_step_samples (algorithm_version, start_at);

-- Projection wipes delete sleep_summaries by start_at; only end_at was indexed.
CREATE INDEX sleep_summaries_start_idx
    ON sleep_summaries (start_at);

-- Every metric table references ingestion_records without an index on the referencing column, so
-- each ingestion_records delete re-scans them all to check the constraint. scalar_samples is the
-- large one (V21 runs exactly such a delete); the rest are small today but cost the same shape.
CREATE INDEX scalar_samples_ingestion_record_idx
    ON scalar_samples (ingestion_record_id);

CREATE INDEX step_samples_ingestion_record_idx
    ON step_samples (ingestion_record_id);

CREATE INDEX sleep_sessions_ingestion_record_idx
    ON sleep_sessions (ingestion_record_id);

CREATE INDEX sleep_summaries_ingestion_record_idx
    ON sleep_summaries (ingestion_record_id);

CREATE INDEX activity_summaries_ingestion_record_idx
    ON activity_summaries (ingestion_record_id);

CREATE INDEX blood_pressure_measurements_ingestion_record_idx
    ON blood_pressure_measurements (ingestion_record_id);
