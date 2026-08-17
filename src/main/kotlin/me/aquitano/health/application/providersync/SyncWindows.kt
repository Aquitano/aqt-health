package me.aquitano.health.application.providersync

import java.time.Duration
import java.time.Instant

/** Largest window both providers accept per request. */
val PROVIDER_SAFE_WINDOW: Duration = Duration.ofDays(31)

/** Pause between consecutive upstream requests during a sync. */
val PROVIDER_REQUEST_INTERVAL: Duration = Duration.ofMillis(500)

data class SyncWindow(
    val from: Instant,
    val to: Instant,
)

/** Splits [from]..[to] into consecutive windows of at most [windowSize], clamping the last one. */
fun syncWindows(from: Instant, to: Instant, windowSize: Duration): List<SyncWindow> {
    val windows = mutableListOf<SyncWindow>()
    var windowFrom = from
    while (windowFrom.isBefore(to)) {
        val windowTo = minOf(windowFrom.plus(windowSize), to)
        windows += SyncWindow(windowFrom, windowTo)
        windowFrom = windowTo
    }
    return windows
}
