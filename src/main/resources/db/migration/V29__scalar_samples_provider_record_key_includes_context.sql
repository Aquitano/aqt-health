-- V25 added context to the NULL-provider-record-id natural key, but the complementary key for
-- rows that do carry a provider record id still ignores it. A provider emitting one record id
-- with two contexts (heart_rate `sleep` plus a general reading) silently loses one row to the
-- batch insert's ON CONFLICT DO NOTHING. Widen the key to include context, matching V25.
-- Widening can only remove conflicts, never add them, so no dedup pass is needed.

DROP INDEX IF EXISTS scalar_samples_provider_record_uq;

CREATE UNIQUE INDEX scalar_samples_provider_record_uq
    ON scalar_samples (
                       source_instance_id,
                       provider_record_id,
                       metric_type,
                       COALESCE(context, ''),
                       COALESCE(segment, '')
        )
    WHERE provider_record_id IS NOT NULL;
