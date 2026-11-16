-- V15 deduplicated sleep across providers per UTC start date. The same night recorded by two
-- devices whose start times straddle UTC midnight (23:55Z and 00:05Z) landed in different
-- partitions and was counted twice, while a lower-ranked device's nap was dropped whenever
-- the better device had any sleep that UTC day.
--
-- Both views now compare actual time overlap. A session is hidden only when a session from
-- another source instance overlaps it and that source ranks better, or ranks the same and has
-- the lower source_instance_id. Overlapping sessions from one source instance (a fragmented
-- night) all survive. A summary is hidden when an overlapping summary ranks better, or ranks
-- the same and is newer, which keeps V15's "newest wins" tie-break.

CREATE OR REPLACE VIEW canonical_sleep_sessions AS
SELECT s.id,
       (s.start_at AT TIME ZONE 'UTC')::date AS date,
       s.source_instance_id,
       s.id                                  AS sleep_session_id,
       s.start_at,
       s.end_at
FROM sleep_sessions s
         JOIN source_instances si ON si.id = s.source_instance_id
         JOIN sources src ON src.id = si.source_id
         LEFT JOIN provider_ranks pr ON pr.family = 'sleep' AND pr.provider_code = src.code
WHERE NOT EXISTS (SELECT 1
                  FROM sleep_sessions s2
                           JOIN source_instances si2 ON si2.id = s2.source_instance_id
                           JOIN sources src2 ON src2.id = si2.source_id
                           LEFT JOIN provider_ranks pr2 ON pr2.family = 'sleep' AND pr2.provider_code = src2.code
                  WHERE s2.source_instance_id <> s.source_instance_id
                    AND s2.start_at < s.end_at
                    AND s2.end_at > s.start_at
                    AND (COALESCE(pr2.rank, 10000) < COALESCE(pr.rank, 10000)
                      OR (COALESCE(pr2.rank, 10000) = COALESCE(pr.rank, 10000)
                          AND s2.source_instance_id < s.source_instance_id)));

CREATE OR REPLACE VIEW canonical_sleep_summaries AS
SELECT s.id,
       (s.start_at AT TIME ZONE 'UTC')::date AS date,
       s.source_instance_id,
       s.id                                  AS sleep_summary_id,
       s.start_at,
       s.end_at
FROM sleep_summaries s
         JOIN source_instances si ON si.id = s.source_instance_id
         JOIN sources src ON src.id = si.source_id
         LEFT JOIN provider_ranks pr ON pr.family = 'sleep_summary' AND pr.provider_code = src.code
WHERE NOT EXISTS (SELECT 1
                  FROM sleep_summaries s2
                           JOIN source_instances si2 ON si2.id = s2.source_instance_id
                           JOIN sources src2 ON src2.id = si2.source_id
                           LEFT JOIN provider_ranks pr2
                                     ON pr2.family = 'sleep_summary' AND pr2.provider_code = src2.code
                  WHERE s2.id <> s.id
                    AND s2.start_at < s.end_at
                    AND s2.end_at > s.start_at
                    AND (COALESCE(pr2.rank, 10000) < COALESCE(pr.rank, 10000)
                      OR (COALESCE(pr2.rank, 10000) = COALESCE(pr.rank, 10000) AND s2.id > s.id)));
