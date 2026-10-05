package me.aquitano.health.application

import me.aquitano.health.domain.RecordTypes
import me.aquitano.health.shared.utcDate
import java.time.Instant
import java.time.LocalDate

interface DerivedRebuildExecutor {
    suspend fun rebuild(
        requests: List<DerivedRebuildRequest>,
        computedAt: Instant,
    )
}

data class DerivedRebuildRequest(
    val sourceInstanceId: Int,
    val affectedStepDates: Set<LocalDate> = emptySet(),
)

/**
 * The single record-to-rebuild-dates mapping shared by the ingestion write path and replay,
 * so a rebuild cannot be wired into one and silently skipped by the other.
 */
fun stepRebuildDates(
    recordType: String,
    startAt: Instant,
    endAt: Instant?,
): Set<LocalDate> {
    if (recordType != RecordTypes.STEP_INTERVAL) return emptySet()
    // Replay rows may carry a null end; treat them as an instant-wide interval.
    val lastIncludedDate = (endAt ?: startAt.plusNanos(1)).minusNanos(1).utcDate()
    return generateSequence(startAt.utcDate()) { it.plusDays(1) }
        .takeWhile { it <= lastIncludedDate }
        .toSet()
}
