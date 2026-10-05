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

/**
 * Splits [from]..[to] into one-day windows anchored to UTC midnight, clamping the last one.
 *
 * Completed days share a stable window key. Manual backfills cache processed batches;
 * scheduled refreshes fetch again and compare content with the latest successful snapshot.
 */
fun dailySyncWindows(
    from: Instant,
    to: Instant,
): List<SyncWindow> =
    generateSequence(from.truncatedTo(ChronoUnit.DAYS)) { it.plus(1, ChronoUnit.DAYS) }
        .takeWhile { it.isBefore(to) }
        .map { SyncWindow(it, minOf(it.plus(1, ChronoUnit.DAYS), to)) }
        .toList()
