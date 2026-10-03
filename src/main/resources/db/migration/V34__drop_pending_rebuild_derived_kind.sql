ALTER TABLE pending_derived_rebuilds DROP COLUMN derived_kind;

ALTER TABLE pending_derived_rebuilds
    ADD CONSTRAINT pending_derived_rebuilds_source_instance_id_affected_date_key
        UNIQUE (source_instance_id, affected_date);
