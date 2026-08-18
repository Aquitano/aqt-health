-- The V22 view ranked providers with a window function partitioned on
-- date_bin('30 seconds', measured_at, ...). A window partition key that is a function of a column
-- blocks predicate pushdown, so a reader asking for one day still scanned the metric's entire
-- history (693 ms vs 0.21 ms for the same range read straight off scalar_samples).
--
-- The NOT EXISTS form expresses the same rule -- within a (metric_type, context, segment, 30s bin)
-- group only the lowest-ranked provider's rows survive, ties keep every row of that rank -- but
-- states the bin as a half-open range on s2.measured_at, which the planner can push down onto
-- scalar_samples_metric_measured_idx.

CREATE OR REPLACE VIEW canonical_scalar_samples AS
SELECT s.id,
       s.source_instance_id,
       s.ingestion_record_id,
       s.provider_record_id,
       s.measured_at,
       s.metric_type,
       s.value,
       mc.unit,
       s.context,
       s.segment,
       s.created_at
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
WHERE NOT EXISTS (SELECT 1
                  FROM scalar_samples s2
                           JOIN source_instances si2 ON si2.id = s2.source_instance_id
                           JOIN sources src2 ON src2.id = si2.source_id
                           LEFT JOIN provider_ranks pr2
                                     ON pr2.provider_code = src2.code
                                         AND pr2.family = CASE
                                                              WHEN s2.metric_type = 'heart_rate' AND s2.context = 'sleep'
                                                                  THEN 'sleep'
                                                              ELSE mc.family
                                                          END
                  WHERE s2.metric_type = s.metric_type
                    AND s2.measured_at >= date_bin('30 seconds', s.measured_at, TIMESTAMPTZ 'epoch')
                    AND s2.measured_at < date_bin('30 seconds', s.measured_at, TIMESTAMPTZ 'epoch')
                      + INTERVAL '30 seconds'
                    AND COALESCE(s2.context, '') = COALESCE(s.context, '')
                    AND COALESCE(s2.segment, '') = COALESCE(s.segment, '')
                    AND COALESCE(pr2.rank, 10000) < COALESCE(pr.rank, 10000));
