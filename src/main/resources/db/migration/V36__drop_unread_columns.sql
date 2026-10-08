-- Columns that were written but never read. Value ranges and segment support are enforced from
-- ScalarMetricRegistry, source display names were only ever NULL, and a checkpoint's
-- last_successful_to always equalled its checkpoint_at.
ALTER TABLE metric_catalog
    DROP COLUMN min_value,
    DROP COLUMN max_value,
    DROP COLUMN supports_segment;

ALTER TABLE sources
    DROP COLUMN display_name;

ALTER TABLE source_instances
    DROP COLUMN display_name;

ALTER TABLE provider_scheduled_sync_checkpoints
    DROP COLUMN last_successful_to;
