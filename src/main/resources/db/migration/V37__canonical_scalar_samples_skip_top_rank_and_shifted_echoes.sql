-- Two changes to canonical_scalar_samples.
--
-- Speed: V27 probed every row's 30-second bin for a better-ranked provider, including rows
-- whose provider already holds rank 0, which nothing can outrank. Heart rate arrives every
-- few seconds, so a week of it ran about 3.5 million probe joins (9 s on real data; 30 days
-- took 25 s). The CASE skips the probe for rank-0 rows. CASE also keeps the planner from
-- turning the NOT EXISTS into an anti-join that probes every row anyway.
--
-- Shifted echoes: some apps that write into Google Health store a scale's local time as UTC,
-- so the same weigh-in comes back hours away from the original and escapes the 30-second
-- bin. A lower-ranked body measurement is hidden when a better-ranked provider recorded the
-- same metric with the same value (to 0.01) a whole number of quarter hours away, up to
-- 14 hours, which covers every UTC offset.

ALTER TABLE provider_ranks
    ADD CONSTRAINT provider_ranks_rank_nonnegative CHECK (rank >= 0);

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
WHERE CASE
          WHEN pr.rank = 0 THEN TRUE
          ELSE NOT EXISTS (SELECT 1
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
                             AND COALESCE(pr2.rank, 10000) < COALESCE(pr.rank, 10000))
              AND (mc.family <> 'body_measurement'
                  OR NOT EXISTS (SELECT 1
                                 FROM scalar_samples s3
                                          JOIN source_instances si3 ON si3.id = s3.source_instance_id
                                          JOIN sources src3 ON src3.id = si3.source_id
                                          LEFT JOIN provider_ranks pr3
                                                    ON pr3.provider_code = src3.code
                                                        AND pr3.family = mc.family
                                 WHERE s3.metric_type = s.metric_type
                                   AND s3.measured_at >= s.measured_at - INTERVAL '14 hours'
                                   AND s3.measured_at <= s.measured_at + INTERVAL '14 hours'
                                   AND s3.measured_at <> s.measured_at
                                   AND EXTRACT(EPOCH FROM s3.measured_at - s.measured_at) % 900 = 0
                                   AND round(s3.value::numeric, 2) = round(s.value::numeric, 2)
                                   AND COALESCE(s3.segment, '') = COALESCE(s.segment, '')
                                   AND COALESCE(pr3.rank, 10000) < COALESCE(pr.rank, 10000)))
          END;
