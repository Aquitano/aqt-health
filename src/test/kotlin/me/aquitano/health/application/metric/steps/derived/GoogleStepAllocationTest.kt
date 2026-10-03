package me.aquitano.health.application.metric.steps.derived

import me.aquitano.health.application.metric.steps.repository.StepSampleRow
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleStepAllocationTest {
    private val origin = Instant.parse("2026-04-19T08:00:00Z")

    @Test
    fun newerArrivalKeepsBothUncoveredTailsRegardlessOfProjectionIds() {
        val neighbor = sample(90, 1, 0, 4, 8)
        val correction = sample(2, 2, 1, 3, 10)
        val spans = resolveGoogleStepSpans(listOf(correction, neighbor), setOf(1))
        assertEquals(listOf(90, 2, 90), spans.map { it.sample.id })
        assertEquals(listOf(2, 10, 2), spans.map(::allocated))
    }

    @Test
    fun sourcesAndOtherProvidersRetainIndependentAllocations() {
        val first = sample(1, 1, 0, 4, 8)
        val second = sample(2, 2, 1, 3, 10)
        assertEquals(18, resolveGoogleStepSpans(listOf(first, second), emptySet()).sumOf(::allocated))
        assertEquals(18, resolveGoogleStepSpans(listOf(first, second.copy(sourceInstanceId = 2)), setOf(1, 2)).sumOf(::allocated))
    }

    @Test
    fun touchingBoundariesAndFullyCoveredRowsDoNotAddEmptySpans() {
        val old = sample(1, 1, 0, 4, 8)
        val first = sample(2, 2, 0, 2, 10)
        val second = sample(3, 3, 2, 4, 20)
        val spans = resolveGoogleStepSpans(listOf(old, second, first), setOf(1))
        assertEquals(listOf(2, 3), spans.map { it.sample.id })
        assertEquals(30, spans.sumOf(::allocated))
    }

    @Test
    fun subsecondOverlapUsesOriginalCumulativeRoundingAcrossMidnight() {
        val midnight = Instant.parse("2026-04-20T00:00:00Z")
        val neighbor = StepSampleRow(1, 1, midnight.minusMillis(500), midnight.plusMillis(500), 8, 1)
        val correction = StepSampleRow(2, 1, midnight.minusMillis(250), midnight.plusMillis(250), 6, 2)
        val spans = resolveGoogleStepSpans(listOf(neighbor, correction), setOf(1))
        fun total(from: Instant, to: Instant) = spans.sumOf {
            allocatedSteps(it.sample.startAt, it.sample.endAt, it.sample.steps, maxOf(from, it.startAt), minOf(to, it.endAt))
        }
        assertEquals(5, total(midnight.minusSeconds(1), midnight))
        assertEquals(5, total(midnight, midnight.plusSeconds(1)))
        assertEquals(10, spans.sumOf(::allocated))
    }

    private fun sample(id: Int, arrival: Int, start: Long, end: Long, steps: Int) =
        StepSampleRow(id, 1, origin.plusSeconds(start), origin.plusSeconds(end), steps, arrival)

    private fun allocated(span: StepAllocationSpan) = allocatedSteps(
        span.sample.startAt, span.sample.endAt, span.sample.steps, span.startAt, span.endAt,
    )
}
