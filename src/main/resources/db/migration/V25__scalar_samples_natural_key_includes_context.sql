-- context is part of a scalar sample's semantic identity (heart_rate `sleep` and general readings
-- are separate rows in canonical_scalar_samples), but the V18 natural key ignored it, so an id-less
-- feed emitting both at the same instant silently lost one. Widen the key to include context.
-- Widening can only remove conflicts, never add them, so no dedup pass is needed. Recreated by name
-- so this applies cleanly whether or not an earlier migration already rebuilt the index.

DROP INDEX IF EXISTS scalar_samples_natural_key_uq;

CREATE UNIQUE INDEX scalar_samples_natural_key_uq
    ON scalar_samples (
                       source_instance_id,
                       metric_type,
                       measured_at,
                       COALESCE(context, ''),
                       COALESCE(segment, '')
        )
    WHERE provider_record_id IS NULL;
