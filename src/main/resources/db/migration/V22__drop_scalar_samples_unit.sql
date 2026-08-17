-- scalar_samples.unit only ever held the metric_catalog unit: ingestion rejects any other
-- value. Drop the column and let the canonical view read the unit from the catalog it
-- already joins, so the read shape is unchanged.

DROP VIEW canonical_scalar_samples;

ALTER TABLE scalar_samples
    DROP COLUMN unit;

CREATE VIEW canonical_scalar_samples AS
SELECT id,
       source_instance_id,
       ingestion_record_id,
       provider_record_id,
       measured_at,
       metric_type,
       value,
       unit,
       context,
       segment,
       created_at
FROM (
    SELECT s.*,
           mc.unit                  AS unit,
           COALESCE(pr.rank, 10000) AS provider_rank,
           MIN(COALESCE(pr.rank, 10000)) OVER (
               PARTITION BY s.metric_type,
                            COALESCE(s.context, ''),
                            COALESCE(s.segment, ''),
                            date_bin('30 seconds', s.measured_at, TIMESTAMPTZ 'epoch')
           ) AS best_rank
    FROM scalar_samples s
             JOIN source_instances si ON si.id = s.source_instance_id
             JOIN sources src ON src.id = si.source_id
             JOIN metric_catalog mc ON mc.metric_type = s.metric_type
             LEFT JOIN provider_ranks pr
                       ON pr.provider_code = src.code
                           AND pr.family = CASE
                                               WHEN s.metric_type = 'heart_rate' AND s.context = 'sleep'
                                                   THEN 'sleep'
                                               ELSE mc.family
                                           END
) ranked
WHERE provider_rank = best_rank;
