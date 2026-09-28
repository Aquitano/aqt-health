package me.aquitano.health.application.metric.steps.derived

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

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
