package me.aquitano.health.application

import me.aquitano.health.infrastructure.time.UtcClock
import io.github.oshai.kotlinlogging.KotlinLogging
import me.aquitano.health.infrastructure.logging.*
import me.aquitano.health.shared.PollingWorker
import java.time.Duration

private val schedulerLogger = KotlinLogging.logger {}

class ScheduledProviderSyncScheduler(
    private val service: ScheduledProviderSyncService,
    private val clock: UtcClock,
    pollInterval: Duration = Duration.ofMinutes(1),
) {
    private val worker = PollingWorker(
        logger = schedulerLogger,
        failureEvent = "scheduled_provider_sync_tick_failed",
        interval = pollInterval,
    ) {
        val processed = service.runDue(clock.now())
        if (processed > 0) {
            schedulerLogger.infoWithContext(
                "scheduled_provider_sync_due_processed",
                "count" to processed,
            )
        }
    }

    fun start() = worker.start()

    fun stop() = worker.stop()
}
