-- A sleep night is just a sleep session labelled with the local date it ended on, with no
-- aggregation of its own. Reads compute it from canonical_sleep_sessions for the requested
-- timezone, so the materialized table and its ranked view are dead weight.

DROP VIEW canonical_sleep_nights;

DROP TABLE sleep_nights;

-- Queued retries for the removed derived kind would fail to decode on the next sweep.
DELETE
FROM pending_derived_rebuilds
WHERE derived_kind = 'SLEEP_NIGHT';
