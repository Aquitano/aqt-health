package me.aquitano.health.application.metric.steps.derived

import me.aquitano.health.application.metric.steps.repository.StepSampleRow
import java.time.Instant
import java.util.TreeSet

internal data class StepAllocationSpan(val sample: StepSampleRow, val startAt: Instant, val endAt: Instant)

/** Google corrections can move accepted intervals over their neighbors. Resolve only the shared
 * time using the saved content-change order; the original sample remains the basis for allocation. */
internal fun resolveGoogleStepSpans(
    samples: List<StepSampleRow>,
    googleSourceIds: Set<Int>,
): List<StepAllocationSpan> {
    val result = mutableListOf<StepAllocationSpan>()
    val googleSamples = mutableMapOf<Int, MutableList<StepSampleRow>>()
    samples.forEach { sample ->
        if (sample.sourceInstanceId in googleSourceIds) {
            googleSamples.getOrPut(sample.sourceInstanceId) { mutableListOf() }.add(sample)
        } else {
            result += StepAllocationSpan(sample, sample.startAt, sample.endAt)
        }
    }
    googleSamples.values.forEach { sourceSamples ->
        val boundaries = sourceSamples.flatMap { sample ->
            listOf(Boundary(sample.startAt, sample, true), Boundary(sample.endAt, sample, false))
        }.sortedBy { it.at }
        val active = TreeSet(compareBy<StepSampleRow> { it.allocationPriority ?: Int.MIN_VALUE }.thenBy { it.id })
        var previous = boundaries.first().at
        var index = 0
        while (index < boundaries.size) {
            val at = boundaries[index].at
            if (previous.isBefore(at) && active.isNotEmpty()) {
                val winner = active.last()
                val last = result.lastOrNull()
                if (last?.sample === winner && last.endAt == previous) {
                    result[result.lastIndex] = last.copy(endAt = at)
                } else {
                    result += StepAllocationSpan(winner, previous, at)
                }
            }
            // All events at a boundary are applied before allocating the next half-open span.
            while (index < boundaries.size && boundaries[index].at == at) {
                val boundary = boundaries[index++]
                if (boundary.starts) active.add(boundary.sample) else active.remove(boundary.sample)
            }
            previous = at
        }
    }
    return result
}

private data class Boundary(val at: Instant, val sample: StepSampleRow, val starts: Boolean)
