package me.aquitano.health.application.metric.steps.derived

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class StepAllocationTest {
    private fun dayWindow(date: LocalDate): Pair<Instant, Instant> =
        date.atStartOfDay(ZoneOffset.UTC).toInstant() to
            date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()

    @Test
    fun subsecondSampleAndAdjacentBucketsPreserveTheTotal() {
        val start = Instant.parse("2026-06-01T23:59:59.750Z")
        val middle = Instant.parse("2026-06-02T00:00:00Z")
        val end = Instant.parse("2026-06-02T00:00:00.250Z")
        assertEquals(2, allocatedSteps(start, end, 3, start, middle))
        assertEquals(1, allocatedSteps(start, end, 3, middle, end))
    }

    @Test
    fun sampleSpanningMidnightAllocatesExactlyItsTotal() {
        // 101 steps split evenly across midnight: independent rounding would yield 51 + 51.
        val start = Instant.parse("2026-06-01T23:00:00Z")
        val end = Instant.parse("2026-06-02T01:00:00Z")
        val (day1Start, day1End) = dayWindow(LocalDate.of(2026, 6, 1))
        val (day2Start, day2End) = dayWindow(LocalDate.of(2026, 6, 2))

        val day1 = allocatedSteps(start, end, 101, day1Start, day1End)
        val day2 = allocatedSteps(start, end, 101, day2Start, day2End)

        assertEquals(101, day1 + day2)
    }

    @Test
    fun sampleInsideOneDayAllocatesEverything() {
        val (dayStart, dayEnd) = dayWindow(LocalDate.of(2026, 6, 1))
        val steps = allocatedSteps(
            Instant.parse("2026-06-01T08:00:00Z"), Instant.parse("2026-06-01T09:00:00Z"), 4321, dayStart, dayEnd,
        )
        assertEquals(4321, steps)
    }

    @Test
    fun sampleOutsideTheDayAllocatesNothing() {
        val (dayStart, dayEnd) = dayWindow(LocalDate.of(2026, 6, 1))
        val steps = allocatedSteps(
            Instant.parse("2026-06-02T08:00:00Z"), Instant.parse("2026-06-02T09:00:00Z"), 500, dayStart, dayEnd,
        )
        assertEquals(0, steps)
    }
}
