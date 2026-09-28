package me.aquitano.health.application.metric.steps.derived

import me.aquitano.health.application.metric.steps.repository.StepDailySummaryDerivationRepository
import org.jetbrains.exposed.v1.jdbc.Database
import me.aquitano.health.infrastructure.database.suspendDbTransaction
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

private const val MAX_SNAPSHOT_ATTEMPTS = 3

class StepDailySummaryDerivation(
    private val repository: StepDailySummaryDerivationRepository,
) {
    suspend fun recompute(
        database: Database,
        sourceInstanceId: Int,
        dates: Set<LocalDate>,
        computedAt: Instant,
    ) {
        dates.forEach { date ->
            val persisted = (1..MAX_SNAPSHOT_ATTEMPTS).any {
                recomputeFromCurrentSamples(database, sourceInstanceId, date, computedAt)
            }
            check(persisted) { "Step samples kept changing while deriving $date; retry required" }
        }
    }

    /** Returns false when raw samples changed between the read and the locked persist. */
    private suspend fun recomputeFromCurrentSamples(
        database: Database,
        sourceInstanceId: Int,
        date: LocalDate,
        computedAt: Instant,
    ): Boolean {
        val dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val samples = suspendDbTransaction(db = database) {
            repository.listStepSamplesOverlapping(sourceInstanceId, dayStart, dayEnd)
        }
        val output = StepDailySummaryOutput(
            sourceInstanceId = sourceInstanceId,
            date = date,
            computedAt = computedAt,
            steps = samples.sumOf { allocatedStepsForDay(it, dayStart, dayEnd) },
            sampleCount = samples.size,
        )
        return suspendDbTransaction(db = database) {
            // Serialize persistence per date, then reject computations made from stale raw rows.
            exec("SELECT pg_advisory_xact_lock(384729, ${date.toEpochDay().toInt()})")
            if (samples != repository.listStepSamplesOverlapping(sourceInstanceId, dayStart, dayEnd)) {
                return@suspendDbTransaction false
            }
            repository.upsertStepDailySummary(output)
            true
        }
    }
}

/**
 * Allocates a sample's steps to the day proportionally to its overlap with the day window.
 * Rounds cumulative allocations at the overlap boundaries and subtracts them, so the
 * per-day allocations of a sample spanning multiple days always sum to sample.steps.
 */
internal fun allocatedStepsForDay(
    sample: StepDailySummaryRawSample,
    dayStart: Instant,
    dayEnd: Instant,
): Int {
    return allocatedSteps(sample.startAt, sample.endAt, sample.steps, dayStart, dayEnd)
}

/** Cumulative rounding preserves the sample total across adjacent days and buckets. */
internal fun allocatedSteps(
    startAt: Instant,
    endAt: Instant,
    steps: Int,
    from: Instant,
    to: Instant,
    duration: Double = secondsBetween(startAt, endAt),
): Int {
    if (duration <= 0) return 0
    val start = maxOf(startAt, from)
    val end = minOf(endAt, to)
    if (!start.isBefore(end)) return 0
    fun cumulative(at: Instant) = (steps * secondsBetween(startAt, at) / duration).roundToInt()
    return cumulative(end) - cumulative(start)
}

internal fun secondsBetween(start: Instant, end: Instant): Double =
    Duration.between(start, end).let { it.seconds + it.nano / 1_000_000_000.0 }

data class StepDailySummaryRawSample(
    val startAt: Instant,
    val endAt: Instant,
    val steps: Int,
)

data class StepDailySummaryOutput(
    val sourceInstanceId: Int,
    val date: LocalDate,
    val computedAt: Instant,
    val steps: Int,
    val sampleCount: Int,
)
