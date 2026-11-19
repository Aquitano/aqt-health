-- Daily metrics: one provider-computed value per calendar day (resting heart rate, nightly HRV,
-- oxygen saturation, ...). They carry a date and no instant, so they get a date-keyed table
-- like activity_summaries instead of scalar_samples.
CREATE TABLE daily_metric_values
(
    id                  SERIAL PRIMARY KEY,
    source_instance_id  INTEGER          NOT NULL REFERENCES source_instances (id),
    ingestion_record_id INTEGER REFERENCES ingestion_records (id),
    provider_record_id  TEXT,
    date                DATE             NOT NULL,
    metric_type         TEXT             NOT NULL,
    value               DOUBLE PRECISION NOT NULL,
    created_at          TIMESTAMPTZ      NOT NULL
);

CREATE UNIQUE INDEX daily_metric_values_provider_record_uq
    ON daily_metric_values (source_instance_id, provider_record_id, metric_type)
    WHERE provider_record_id IS NOT NULL;

CREATE UNIQUE INDEX daily_metric_values_natural_key_uq
    ON daily_metric_values (source_instance_id, date, metric_type)
    WHERE provider_record_id IS NULL;

CREATE INDEX daily_metric_values_metric_date_idx ON daily_metric_values (metric_type, date);
CREATE INDEX daily_metric_values_ingestion_record_idx ON daily_metric_values (ingestion_record_id);

CREATE VIEW canonical_daily_metric_values AS
SELECT DISTINCT ON (d.date, d.metric_type) d.id,
                                           d.date,
                                           d.metric_type,
                                           d.source_instance_id,
                                           d.value
FROM daily_metric_values d
         JOIN source_instances si ON si.id = d.source_instance_id
         JOIN sources src ON src.id = si.source_id
         LEFT JOIN provider_ranks pr ON pr.family = 'daily_metric' AND pr.provider_code = src.code
ORDER BY d.date, d.metric_type, COALESCE(pr.rank, 10000), d.id DESC;

ALTER TABLE ingestion_records
    DROP CONSTRAINT ingestion_records_record_type_check;

ALTER TABLE ingestion_records
    ADD CONSTRAINT ingestion_records_record_type_check
        CHECK (record_type IN (
            'step_interval',
            'sleep_session',
            'activity_summary',
            'sleep_summary',
            'blood_pressure',
            'scalar',
            'daily_metric'
        ));
