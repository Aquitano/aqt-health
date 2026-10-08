package me.aquitano.health.test

import kotlinx.coroutines.CompletableDeferred
import me.aquitano.health.domain.HealthProvider
import me.aquitano.health.domain.HealthProviderDescriptor
import me.aquitano.health.domain.ProviderAuthType
import me.aquitano.health.domain.ProviderConnection
import me.aquitano.health.domain.ProviderSyncProgressSink
import me.aquitano.health.domain.ProviderSyncRequest
import me.aquitano.health.domain.ProviderSyncSummary
import me.aquitano.health.domain.ProviderWorkflowEndpoints
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** A provider without OAuth that syncs steps and reports every sync as processed. */
open class FakeProvider(
    final override val providerCode: String,
) : HealthProvider {
    val syncCalls = AtomicInteger(0)
    val requests = mutableListOf<ProviderSyncRequest>()

    override val defaultProviderInstanceId = "${providerCode.replace('_', '-')}-me"

    override val descriptor =
        HealthProviderDescriptor(
            providerCode = providerCode,
            displayName = providerCode,
            authType = ProviderAuthType.NONE,
            requiresAuthentication = false,
            supportedDataTypes = listOf("steps"),
            defaultDataTypes = listOf("steps"),
            maxSyncRangeDays = 31,
            supportsPageSize = false,
            workflowEndpoints = ProviderWorkflowEndpoints(sync = "/sync"),
        )

    override fun isConfigured(): Boolean = true

    override fun getAuthUrl(state: String): String = error("OAuth is not supported")

    override suspend fun connect(
        code: String,
        now: Instant,
    ): ProviderConnection = error("OAuth is not supported")

    override suspend fun sync(
        request: ProviderSyncRequest,
        now: Instant,
        progress: ProviderSyncProgressSink,
    ): ProviderSyncSummary {
        syncCalls.incrementAndGet()
        requests += request
        return ProviderSyncSummary(
            providerCode = providerCode,
            providerInstanceId = request.providerInstanceId ?: defaultProviderInstanceId,
            requestedFrom = request.from,
            requestedTo = request.to,
            status = "processed",
            batches = emptyList(),
            errors = emptyList(),
        )
    }
}

/** Holds every sync open until [release] completes, so tests can observe a running job. */
class BlockingProvider : FakeProvider("blocking_provider") {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun sync(
        request: ProviderSyncRequest,
        now: Instant,
        progress: ProviderSyncProgressSink,
    ): ProviderSyncSummary {
        val summary = super.sync(request, now, progress)
        started.complete(Unit)
        release.await()
        return summary
    }
}
