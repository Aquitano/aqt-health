package me.aquitano.health.application.metric.steps.derived

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class StepDailySummaryAllocationTest {
    private fun dayWindow(date: LocalDate): Pair<Instant, Instant> =
        date.atStartOfDay(ZoneOffset.UTC).toInstant() to
            date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()

    @Test
    fun sampleSpanningMidnightAllocatesExactlyItsTotal() {
        // 101 steps split evenly across midnight: independent rounding would yield 51 + 51.
        val sample = StepDailySummaryRawSample(
            startAt = Instant.parse("2026-06-01T23:00:00Z"),
            endAt = Instant.parse("2026-06-02T01:00:00Z"),
            steps = 101,
        )
        val (day1Start, day1End) = dayWindow(LocalDate.of(2026, 6, 1))
        val (day2Start, day2End) = dayWindow(LocalDate.of(2026, 6, 2))

        val day1 = allocatedStepsForDay(sample, day1Start, day1End)
        val day2 = allocatedStepsForDay(sample, day2Start, day2End)

        assertEquals(sample.steps, day1 + day2)
    }

    @Test
    fun sampleInsideOneDayAllocatesEverything() {
        val sample = StepDailySummaryRawSample(
            startAt = Instant.parse("2026-06-01T08:00:00Z"),
            endAt = Instant.parse("2026-06-01T09:00:00Z"),
            steps = 4321,
        )
        val (dayStart, dayEnd) = dayWindow(LocalDate.of(2026, 6, 1))

        assertEquals(4321, allocatedStepsForDay(sample, dayStart, dayEnd))
    }

    @Test
    fun sampleOutsideTheDayAllocatesNothing() {
        val sample = StepDailySummaryRawSample(
            startAt = Instant.parse("2026-06-02T08:00:00Z"),
            endAt = Instant.parse("2026-06-02T09:00:00Z"),
            steps = 500,
        )
        val (dayStart, dayEnd) = dayWindow(LocalDate.of(2026, 6, 1))

        assertEquals(0, allocatedStepsForDay(sample, dayStart, dayEnd))
    }
}
