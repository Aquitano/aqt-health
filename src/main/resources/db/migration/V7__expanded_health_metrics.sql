CREATE TABLE activity_summaries
(
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    source_instance_id  INTEGER NOT NULL REFERENCES source_instances (id),
    ingestion_record_id INTEGER REFERENCES ingestion_records (id),
    provider_record_id  TEXT,
    date                TEXT    NOT NULL,
    distance_meters     REAL,
    active_energy_kcal  REAL,
    total_energy_kcal   REAL,
    elevation_meters    REAL,
    soft_minutes        INTEGER,
    moderate_minutes    INTEGER,
    intense_minutes     INTEGER,
    active_minutes      INTEGER,
    avg_heart_rate_bpm  INTEGER,
    min_heart_rate_bpm  INTEGER,
    max_heart_rate_bpm  INTEGER,
    created_at          TEXT    NOT NULL
);

CREATE UNIQUE INDEX activity_summaries_source_instance_provider_record_id_uq
    ON activity_summaries (source_instance_id, provider_record_id)
    WHERE provider_record_id IS NOT NULL;

CREATE TABLE sleep_summaries
(
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    source_instance_id      INTEGER NOT NULL REFERENCES source_instances (id),
    ingestion_record_id     INTEGER REFERENCES ingestion_records (id),
    provider_record_id      TEXT,
    start_at                TEXT    NOT NULL,
    end_at                  TEXT    NOT NULL,
    time_in_bed_seconds     INTEGER,
    total_sleep_seconds     INTEGER,
    light_sleep_seconds     INTEGER,
    deep_sleep_seconds      INTEGER,
    rem_sleep_seconds       INTEGER,
    sleep_efficiency_percent REAL,
    sleep_latency_seconds   INTEGER,
    wakeup_latency_seconds  INTEGER,
    wakeup_duration_seconds INTEGER,
    wakeup_count            INTEGER,
    waso_seconds            INTEGER,
    sleep_score             INTEGER,
    created_at              TEXT    NOT NULL
);

CREATE UNIQUE INDEX sleep_summaries_source_instance_provider_record_id_uq
    ON sleep_summaries (source_instance_id, provider_record_id)
    WHERE provider_record_id IS NOT NULL;

CREATE TABLE respiratory_rate_samples
(
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    source_instance_id  INTEGER NOT NULL REFERENCES source_instances (id),
    ingestion_record_id INTEGER REFERENCES ingestion_records (id),
    provider_record_id  TEXT,
    measured_at         TEXT    NOT NULL,
    breaths_per_minute  INTEGER NOT NULL,
    context             TEXT,
    created_at          TEXT    NOT NULL
);

CREATE UNIQUE INDEX respiratory_rate_samples_source_instance_provider_record_id_uq
    ON respiratory_rate_samples (source_instance_id, provider_record_id)
    WHERE provider_record_id IS NOT NULL;

CREATE TABLE hrv_samples
(
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    source_instance_id  INTEGER NOT NULL REFERENCES source_instances (id),
    ingestion_record_id INTEGER REFERENCES ingestion_records (id),
    provider_record_id  TEXT,
    measured_at         TEXT    NOT NULL,
    metric_type         TEXT    NOT NULL,
    value               REAL    NOT NULL,
    unit                TEXT    NOT NULL,
    context             TEXT,
    created_at          TEXT    NOT NULL
);

CREATE UNIQUE INDEX hrv_samples_source_instance_provider_record_id_metric_type_uq
    ON hrv_samples (source_instance_id, provider_record_id, metric_type)
    WHERE provider_record_id IS NOT NULL;

DROP TRIGGER IF EXISTS ingestion_records_type_insert_ck;
DROP TRIGGER IF EXISTS ingestion_records_type_update_ck;

CREATE TRIGGER ingestion_records_type_insert_ck
    BEFORE INSERT ON ingestion_records
    WHEN NEW.record_type NOT IN (
        'step_interval',
        'sleep_session',
        'body_measurement',
        'heart_rate',
        'activity_summary',
        'sleep_summary',
        'respiratory_rate',
        'hrv'
    )
BEGIN
    SELECT RAISE(ABORT, 'invalid ingestion record type');
END;

CREATE TRIGGER ingestion_records_type_update_ck
    BEFORE UPDATE OF record_type ON ingestion_records
    WHEN NEW.record_type NOT IN (
        'step_interval',
        'sleep_session',
        'body_measurement',
        'heart_rate',
        'activity_summary',
        'sleep_summary',
        'respiratory_rate',
        'hrv'
    )
BEGIN
    SELECT RAISE(ABORT, 'invalid ingestion record type');
END;

CREATE TRIGGER activity_summaries_insert_ck
    BEFORE INSERT ON activity_summaries
    WHEN (NEW.distance_meters IS NULL
        AND NEW.active_energy_kcal IS NULL
        AND NEW.total_energy_kcal IS NULL
        AND NEW.elevation_meters IS NULL
        AND NEW.soft_minutes IS NULL
        AND NEW.moderate_minutes IS NULL
        AND NEW.intense_minutes IS NULL
        AND NEW.active_minutes IS NULL
        AND NEW.avg_heart_rate_bpm IS NULL
        AND NEW.min_heart_rate_bpm IS NULL
        AND NEW.max_heart_rate_bpm IS NULL)
        OR NEW.distance_meters < 0
        OR NEW.active_energy_kcal < 0
        OR NEW.total_energy_kcal < 0
        OR NEW.elevation_meters < 0
        OR NEW.soft_minutes < 0
        OR NEW.moderate_minutes < 0
        OR NEW.intense_minutes < 0
        OR NEW.active_minutes < 0
        OR (NEW.avg_heart_rate_bpm IS NOT NULL AND NEW.avg_heart_rate_bpm NOT BETWEEN 25 AND 250)
        OR (NEW.min_heart_rate_bpm IS NOT NULL AND NEW.min_heart_rate_bpm NOT BETWEEN 25 AND 250)
        OR (NEW.max_heart_rate_bpm IS NOT NULL AND NEW.max_heart_rate_bpm NOT BETWEEN 25 AND 250)
BEGIN
    SELECT RAISE(ABORT, 'invalid activity summary');
END;

CREATE TRIGGER activity_summaries_update_ck
    BEFORE UPDATE OF distance_meters, active_energy_kcal, total_energy_kcal, elevation_meters,
        soft_minutes, moderate_minutes, intense_minutes, active_minutes,
        avg_heart_rate_bpm, min_heart_rate_bpm, max_heart_rate_bpm ON activity_summaries
    WHEN (NEW.distance_meters IS NULL
        AND NEW.active_energy_kcal IS NULL
        AND NEW.total_energy_kcal IS NULL
        AND NEW.elevation_meters IS NULL
        AND NEW.soft_minutes IS NULL
        AND NEW.moderate_minutes IS NULL
        AND NEW.intense_minutes IS NULL
        AND NEW.active_minutes IS NULL
        AND NEW.avg_heart_rate_bpm IS NULL
        AND NEW.min_heart_rate_bpm IS NULL
        AND NEW.max_heart_rate_bpm IS NULL)
        OR NEW.distance_meters < 0
        OR NEW.active_energy_kcal < 0
        OR NEW.total_energy_kcal < 0
        OR NEW.elevation_meters < 0
        OR NEW.soft_minutes < 0
        OR NEW.moderate_minutes < 0
        OR NEW.intense_minutes < 0
        OR NEW.active_minutes < 0
        OR (NEW.avg_heart_rate_bpm IS NOT NULL AND NEW.avg_heart_rate_bpm NOT BETWEEN 25 AND 250)
        OR (NEW.min_heart_rate_bpm IS NOT NULL AND NEW.min_heart_rate_bpm NOT BETWEEN 25 AND 250)
        OR (NEW.max_heart_rate_bpm IS NOT NULL AND NEW.max_heart_rate_bpm NOT BETWEEN 25 AND 250)
BEGIN
    SELECT RAISE(ABORT, 'invalid activity summary');
END;

CREATE TRIGGER sleep_summaries_insert_ck
    BEFORE INSERT ON sleep_summaries
    WHEN NEW.start_at >= NEW.end_at
        OR (NEW.time_in_bed_seconds IS NULL
        AND NEW.total_sleep_seconds IS NULL
        AND NEW.light_sleep_seconds IS NULL
        AND NEW.deep_sleep_seconds IS NULL
        AND NEW.rem_sleep_seconds IS NULL
        AND NEW.sleep_efficiency_percent IS NULL
        AND NEW.sleep_latency_seconds IS NULL
        AND NEW.wakeup_latency_seconds IS NULL
        AND NEW.wakeup_duration_seconds IS NULL
        AND NEW.wakeup_count IS NULL
        AND NEW.waso_seconds IS NULL
        AND NEW.sleep_score IS NULL)
        OR NEW.time_in_bed_seconds < 0
        OR NEW.total_sleep_seconds < 0
        OR NEW.light_sleep_seconds < 0
        OR NEW.deep_sleep_seconds < 0
        OR NEW.rem_sleep_seconds < 0
        OR NEW.sleep_latency_seconds < 0
        OR NEW.wakeup_latency_seconds < 0
        OR NEW.wakeup_duration_seconds < 0
        OR NEW.wakeup_count < 0
        OR NEW.waso_seconds < 0
        OR (NEW.sleep_efficiency_percent IS NOT NULL AND (NEW.sleep_efficiency_percent < 0 OR NEW.sleep_efficiency_percent > 100))
        OR (NEW.sleep_score IS NOT NULL AND (NEW.sleep_score < 0 OR NEW.sleep_score > 100))
BEGIN
    SELECT RAISE(ABORT, 'invalid sleep summary');
END;

CREATE TRIGGER sleep_summaries_update_ck
    BEFORE UPDATE OF start_at, end_at, time_in_bed_seconds, total_sleep_seconds, light_sleep_seconds,
        deep_sleep_seconds, rem_sleep_seconds, sleep_efficiency_percent, sleep_latency_seconds,
        wakeup_latency_seconds, wakeup_duration_seconds, wakeup_count, waso_seconds, sleep_score ON sleep_summaries
    WHEN NEW.start_at >= NEW.end_at
        OR (NEW.time_in_bed_seconds IS NULL
        AND NEW.total_sleep_seconds IS NULL
        AND NEW.light_sleep_seconds IS NULL
        AND NEW.deep_sleep_seconds IS NULL
        AND NEW.rem_sleep_seconds IS NULL
        AND NEW.sleep_efficiency_percent IS NULL
        AND NEW.sleep_latency_seconds IS NULL
        AND NEW.wakeup_latency_seconds IS NULL
        AND NEW.wakeup_duration_seconds IS NULL
        AND NEW.wakeup_count IS NULL
        AND NEW.waso_seconds IS NULL
        AND NEW.sleep_score IS NULL)
        OR NEW.time_in_bed_seconds < 0
        OR NEW.total_sleep_seconds < 0
        OR NEW.light_sleep_seconds < 0
        OR NEW.deep_sleep_seconds < 0
        OR NEW.rem_sleep_seconds < 0
        OR NEW.sleep_latency_seconds < 0
        OR NEW.wakeup_latency_seconds < 0
        OR NEW.wakeup_duration_seconds < 0
        OR NEW.wakeup_count < 0
        OR NEW.waso_seconds < 0
        OR (NEW.sleep_efficiency_percent IS NOT NULL AND (NEW.sleep_efficiency_percent < 0 OR NEW.sleep_efficiency_percent > 100))
        OR (NEW.sleep_score IS NOT NULL AND (NEW.sleep_score < 0 OR NEW.sleep_score > 100))
BEGIN
    SELECT RAISE(ABORT, 'invalid sleep summary');
END;

CREATE TRIGGER respiratory_rate_samples_insert_ck
    BEFORE INSERT ON respiratory_rate_samples
    WHEN NEW.breaths_per_minute NOT BETWEEN 5 AND 80
        OR NEW.context NOT IN ('sleep', 'resting', 'general', 'unknown')
BEGIN
    SELECT RAISE(ABORT, 'invalid respiratory rate sample');
END;

CREATE TRIGGER respiratory_rate_samples_update_ck
    BEFORE UPDATE OF breaths_per_minute, context ON respiratory_rate_samples
    WHEN NEW.breaths_per_minute NOT BETWEEN 5 AND 80
        OR NEW.context NOT IN ('sleep', 'resting', 'general', 'unknown')
BEGIN
    SELECT RAISE(ABORT, 'invalid respiratory rate sample');
END;

CREATE TRIGGER hrv_samples_insert_ck
    BEFORE INSERT ON hrv_samples
    WHEN NEW.metric_type NOT IN ('rmssd')
        OR NEW.unit != 'ms'
        OR NEW.value <= 0
        OR NEW.value > 500
        OR NEW.context NOT IN ('sleep', 'resting', 'general', 'unknown')
BEGIN
    SELECT RAISE(ABORT, 'invalid hrv sample');
END;

CREATE TRIGGER hrv_samples_update_ck
    BEFORE UPDATE OF metric_type, value, unit, context ON hrv_samples
    WHEN NEW.metric_type NOT IN ('rmssd')
        OR NEW.unit != 'ms'
        OR NEW.value <= 0
        OR NEW.value > 500
        OR NEW.context NOT IN ('sleep', 'resting', 'general', 'unknown')
BEGIN
    SELECT RAISE(ABORT, 'invalid hrv sample');
END;

CREATE INDEX activity_summaries_source_instance_date_idx
    ON activity_summaries (source_instance_id, date);

CREATE INDEX activity_summaries_date_idx
    ON activity_summaries (date);

CREATE INDEX sleep_summaries_source_instance_end_idx
    ON sleep_summaries (source_instance_id, end_at);

CREATE INDEX sleep_summaries_end_idx
    ON sleep_summaries (end_at);

CREATE INDEX respiratory_rate_samples_source_instance_measured_idx
    ON respiratory_rate_samples (source_instance_id, measured_at);

CREATE INDEX respiratory_rate_samples_measured_idx
    ON respiratory_rate_samples (measured_at);

CREATE INDEX hrv_samples_source_instance_metric_measured_idx
    ON hrv_samples (source_instance_id, metric_type, measured_at);

CREATE INDEX hrv_samples_metric_measured_idx
    ON hrv_samples (metric_type, measured_at);
