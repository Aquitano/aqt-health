package me.aquitano.health.application.metric.steps.derived

import me.aquitano.health.application.metric.steps.repository.StepDailySummaryDerivationRepository
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToInt

private const val STEP_DAILY_SUMMARY_ALGORITHM_VERSION = 1

class StepDailySummaryDerivation(
    private val repository: StepDailySummaryDerivationRepository,
) {
    suspend fun recompute(
        sourceInstanceId: Int,
        dates: Set<LocalDate>,
        computedAt: Instant,
    ) {
        dates.forEach { date ->
            val dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
            val samples = repository.listStepSamplesOverlapping(
                sourceInstanceId = sourceInstanceId,
                dayStart = dayStart,
                dayEnd = dayEnd,
            )
            repository.upsertStepDailySummary(
                StepDailySummaryOutput(
                    sourceInstanceId = sourceInstanceId,
                    date = date,
                    timezone = ZoneOffset.UTC,
                    algorithmVersion = STEP_DAILY_SUMMARY_ALGORITHM_VERSION,
                    computedAt = computedAt,
                    steps = samples.sumOf { allocatedStepsForDay(it, dayStart, dayEnd) },
                    sampleCount = samples.size,
                )
            )
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
    val totalSeconds = Duration.between(sample.startAt, sample.endAt).seconds
    if (totalSeconds <= 0) return 0

    val overlapStart = maxOf(sample.startAt, dayStart)
    val overlapEnd = minOf(sample.endAt, dayEnd)
    if (!overlapStart.isBefore(overlapEnd)) return 0

    fun cumulativeSteps(at: Instant): Int =
        (sample.steps.toDouble() * Duration.between(sample.startAt, at).seconds / totalSeconds).roundToInt()

    return cumulativeSteps(overlapEnd) - cumulativeSteps(overlapStart)
}

data class StepDailySummaryRawSample(
    val startAt: Instant,
    val endAt: Instant,
    val steps: Int,
)

data class StepDailySummaryOutput(
    val sourceInstanceId: Int,
    val date: LocalDate,
    val timezone: ZoneId,
    val algorithmVersion: Int,
    val computedAt: Instant,
    val steps: Int,
    val sampleCount: Int,
)
