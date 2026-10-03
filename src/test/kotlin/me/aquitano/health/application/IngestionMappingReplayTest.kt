package me.aquitano.health.application

import me.aquitano.health.api.dto.*
import kotlin.test.Test
import kotlin.test.assertNull

class IngestionMappingReplayTest {
    @Test
    fun replayRejectsStructuralRecordsWithValidationIssues() {
        val mapper = IngestionMappingService()
        listOf(
            ActivitySummary(date = "2026-06-01", distanceMeters = -1.0),
            SleepSummary(startAt = "2026-06-01T00:00:00Z", endAt = "2026-06-01T08:00:00Z", sleepScore = 101),
            BloodPressure(measuredAt = "2026-06-01T00:00:00Z", systolicMmhg = 120, diastolicMmhg = 80, heartRateBpm = 1),
            SleepSession(
                startAt = "2026-06-01T00:00:00Z",
                endAt = "2026-06-01T08:00:00Z",
                stages =
                    listOf(
                        SleepStage("deep", "2026-05-31T23:00:00Z", "2026-06-01T01:00:00Z"),
                    ),
            ),
        ).forEach { assertNull(mapper.mapRecord(it), "Rejected during ingestion must also be rejected during replay: $it") }
    }
}
