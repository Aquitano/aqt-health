package me.aquitano.health.shared

import io.github.oshai.kotlinlogging.KLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * Single background poll loop: runs [tick] every [interval] until stopped. A failing tick is
 * logged as [failureEvent] and swallowed so one bad pass cannot end the loop; cancellation is
 * rethrown so shutdown still stops it.
 */
class PollingWorker(
    private val logger: KLogger,
    private val failureEvent: String,
    private val interval: Duration,
    private val tick: suspend () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    logger.error(exception) { failureEvent }
                }
                delay(interval.toMillis())
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope.cancel()
    }
}
