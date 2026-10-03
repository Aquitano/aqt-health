package me.aquitano.health.application.metric.common

import java.time.Instant
import java.util.TreeMap

data class CanonicalIntervalCandidate<T>(
    val row: T,
    val sourceInstanceId: Int,
    val startAt: Instant,
    val endAt: Instant,
)

fun <T> canonicalIntervalRows(
    rows: List<CanonicalIntervalCandidate<T>>,
    choosePreferred: (CanonicalIntervalCandidate<T>, CanonicalIntervalCandidate<T>) -> CanonicalIntervalCandidate<T>,
): List<T> {
    val selected = linkedSetOf<CanonicalIntervalCandidate<T>>()
    val activeBySource = mutableMapOf<Int, TreeMap<Instant, MutableList<CanonicalIntervalCandidate<T>>>>()
    rows.forEach { row ->
        activeBySource.entries.removeIf { (_, intervals) ->
            intervals.pruneEndedAtOrBefore(row.startAt)
            intervals.isEmpty()
        }
        val overlapping =
            activeBySource
                .asSequence()
                .filter { (sourceInstanceId, _) -> sourceInstanceId != row.sourceInstanceId }
                .flatMap { (_, intervalsByEnd) -> intervalsByEnd.values.asSequence().flatMap { it.asSequence() } }
                .filter { it.startAt.isBefore(row.endAt) && row.startAt.isBefore(it.endAt) }
                .toList()
        if (overlapping.isEmpty()) {
            selected.add(row)
            activeBySource.add(row)
        } else {
            val candidateWinsAll =
                overlapping.all { existing ->
                    choosePreferred(existing, row) == row
                }
            if (candidateWinsAll) {
                overlapping.forEach {
                    selected.remove(it)
                    activeBySource.remove(it)
                }
                selected.add(row)
                activeBySource.add(row)
            }
        }
    }
    return selected.sortedWith(compareBy<CanonicalIntervalCandidate<T>> { it.startAt }.thenBy { it.endAt }).map { it.row }
}

private fun <T> MutableMap<Int, TreeMap<Instant, MutableList<CanonicalIntervalCandidate<T>>>>.add(
    candidate: CanonicalIntervalCandidate<T>,
) {
    getOrPut(candidate.sourceInstanceId) { TreeMap() }
        .getOrPut(candidate.endAt) { mutableListOf() }
        .add(candidate)
}

private fun <T> MutableMap<Int, TreeMap<Instant, MutableList<CanonicalIntervalCandidate<T>>>>.remove(
    candidate: CanonicalIntervalCandidate<T>,
) {
    val intervalsByEnd = this[candidate.sourceInstanceId] ?: return
    val candidates = intervalsByEnd[candidate.endAt] ?: return
    candidates.remove(candidate)
    if (candidates.isEmpty()) {
        intervalsByEnd.remove(candidate.endAt)
    }
    if (intervalsByEnd.isEmpty()) {
        remove(candidate.sourceInstanceId)
    }
}

private fun <T> TreeMap<Instant, MutableList<CanonicalIntervalCandidate<T>>>.pruneEndedAtOrBefore(start: Instant) {
    while (isNotEmpty() && !firstKey().isAfter(start)) {
        pollFirstEntry()
    }
}
