package me.aquitano.health.application.providersync

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Pause between consecutive upstream requests during a sync. */
val PROVIDER_REQUEST_INTERVAL: Duration = Duration.ofMillis(500)

data class SyncWindow(
    val from: Instant,
    val to: Instant,
)

/** Splits [from]..[to] into consecutive windows of at most [windowSize], clamping the last one. */
fun syncWindows(
    from: Instant,
    to: Instant,
    windowSize: Duration,
): List<SyncWindow> {
    require(!windowSize.isZero && !windowSize.isNegative) { "windowSize must be positive" }
    val windows = mutableListOf<SyncWindow>()
    var windowFrom = from
    while (windowFrom.isBefore(to)) {
        val windowTo = minOf(windowFrom.plus(windowSize), to)
        windows += SyncWindow(windowFrom, windowTo)
        windowFrom = windowTo
    }
    return windows
}

/**
 * Splits [from]..[to] into one-day windows anchored to UTC midnight.
 *
 * Completed days share a stable window key. Manual backfills cache processed batches;
 * scheduled refreshes fetch again and compare content with the latest successful snapshot.
 */
fun dailySyncWindows(
    from: Instant,
    to: Instant,
): List<SyncWindow> = syncWindows(from.truncatedTo(ChronoUnit.DAYS), to, Duration.ofDays(1))
