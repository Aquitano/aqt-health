package me.aquitano.health.application

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.aquitano.health.api.dto.ProviderSyncRequest
import me.aquitano.health.application.providersync.ProviderSyncProgressSink
import me.aquitano.health.domain.ConflictException
import me.aquitano.health.domain.HealthProvider
import me.aquitano.health.domain.HealthProviderDescriptor
import me.aquitano.health.domain.ProviderAuthType
import me.aquitano.health.domain.ProviderConnection
import me.aquitano.health.domain.ProviderSyncSummary
import me.aquitano.health.domain.ProviderWorkflowEndpoints
import me.aquitano.health.domain.RequestValidationException
import me.aquitano.health.domain.ValidationIssueCodes
import me.aquitano.health.infrastructure.repositories.ProviderOAuthRepository
import me.aquitano.health.infrastructure.repositories.ProviderSyncIdempotencyRepository
import me.aquitano.health.infrastructure.repositories.ScheduledSyncRepository
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.PostgresTestDatabase
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import me.aquitano.health.domain.ProviderSyncRequest as DomainProviderSyncRequest

class ProviderWorkflowServiceTest : PostgresIntegrationTest() {
    private val now = Instant.parse("2026-05-01T10:00:00Z")

    // No providerInstanceId: the removed canReplaySafely gate used to skip idempotent replay for
    // these requests, so the provider ran on every duplicate.
    private fun request(
        from: String = "2026-05-01T00:00:00Z",
        to: String = "2026-05-08T00:00:00Z",
    ) = ProviderSyncRequest(from = from, to = to, dataTypes = listOf("steps"))

    @Test
    fun syncWithoutProviderInstanceIdReplaysStoredResponseForSameKey() =
        runBlocking {
            val provider = CountingProvider()
            val service = serviceWith(provider)
            val key = "workflow-replay-key"

            val first = service.sync(provider.providerCode, request(), now, key)
            val second = service.sync(provider.providerCode, request(), now, key)

            assertEquals(first, second)
            assertEquals(1, provider.syncCalls.get())
        }

    @Test
    fun syncSameKeyDifferentRequestConflictsWithoutProviderInstanceId() =
        runBlocking {
            val provider = CountingProvider()
            val service = serviceWith(provider)
            val key = "workflow-conflict-key"

            service.sync(provider.providerCode, request(), now, key)
            val conflict =
                assertFailsWith<ConflictException> {
                    service.sync(provider.providerCode, request(to = "2026-05-09T00:00:00Z"), now, key)
                }

            assertEquals("idempotency_key_conflict", conflict.code)
            assertEquals(1, provider.syncCalls.get())
        }

    @Test
    fun concurrentSyncWithSameKeyExecutesProviderOnce() =
        runBlocking {
            val provider = BlockingProvider()
            val service = serviceWith(provider)
            val key = "workflow-concurrent-key"

            val responses =
                coroutineScope {
                    val first = async { service.sync(provider.providerCode, request(), now, key) }
                    provider.started.await()
                    val second = async { service.sync(provider.providerCode, request(), now, key) }
                    provider.release.complete(Unit)
                    listOf(first, second).awaitAll()
                }

            assertEquals(responses[0], responses[1])
            assertEquals(1, provider.syncCalls.get())
        }

    @Test
    fun syncWithoutKeyExecutesEveryTime() =
        runBlocking {
            val provider = CountingProvider()
            val service = serviceWith(provider)

            service.sync(provider.providerCode, request(), now, idempotencyKey = null)
            service.sync(provider.providerCode, request(), now, idempotencyKey = null)

            assertEquals(2, provider.syncCalls.get())
        }

    @Test
    fun syncRangeBeyondTheCeilingIsRejected() {
        // from=1970 would otherwise expand into ~20k throttled daily windows in one job.
        val error =
            assertFailsWith<RequestValidationException> {
                request(from = "1970-01-01T00:00:00Z", to = "2026-05-01T00:00:00Z").toDomain(now)
            }

        assertEquals(listOf("from"), error.issues.map { it.field })
        assertEquals(
            listOf(ValidationIssueCodes.InvalidRange),
            error.issues.map { it.code },
        )
    }

    @Test
    fun syncRangeAtTheCeilingIsAccepted() {
        val from = now.minus(MAX_PROVIDER_SYNC_RANGE)

        val domain = request(from = from.toString(), to = now.toString()).toDomain(now)

        assertEquals(from, domain.from)
        assertEquals(now, domain.to)
    }

    @Test
    fun completingOAuthResumesAParkedSchedule() =
        runBlocking {
            val provider = ConnectingProvider()
            val database = openDatabase(PostgresTestDatabase.config())
            val scheduledSyncRepository = ScheduledSyncRepository(database)
            val parked =
                scheduledSyncRepository.upsertConfig(
                    providerCode = provider.providerCode,
                    providerInstanceId = provider.defaultProviderInstanceId,
                    enabled = true,
                    dataTypes = listOf("steps"),
                    cadenceMinutes = 1_440,
                    lookbackDays = 7,
                    nextRunAt = null,
                    now = now,
                )
            scheduledSyncRepository.markFailure(parked.id, failureCount = 3, nextRunAt = null, errorMessage = "needs reauth", now = now)
            ProviderOAuthRepository(database).insertState("reconnect-state", provider.providerCode, now, now.plusSeconds(600))

            serviceWith(provider, database).completeOAuth(provider.providerCode, "code", "reconnect-state", null, now)

            val resumed = scheduledSyncRepository.getConfig(provider.providerCode, provider.defaultProviderInstanceId)!!
            assertEquals(now, resumed.nextRunAt)
            assertEquals(0, resumed.failureCount)
        }

    private fun serviceWith(
        provider: HealthProvider,
        database: Database = openDatabase(PostgresTestDatabase.config()),
    ): ProviderWorkflowService {
        val registry = HealthProviderRegistry(listOf(provider))
        val oAuthRepository = ProviderOAuthRepository(database)
        return ProviderWorkflowService(
            providerRegistry = registry,
            providerOAuthRepository = oAuthRepository,
            providerStatusService = ProviderStatusService(registry, oAuthRepository),
            syncIdempotencyRepository = ProviderSyncIdempotencyRepository(database),
            scheduledSyncRepository = ScheduledSyncRepository(database),
        )
    }

    private class ConnectingProvider : HealthProvider {
        override val providerCode = "connecting_provider"
        override val defaultProviderInstanceId = "connecting-provider-me"
        override val descriptor = descriptorFor(providerCode, "Connecting Provider")

        override fun isConfigured(): Boolean = true

        override fun getAuthUrl(state: String): String = "https://example.test/auth?state=$state"

        override suspend fun connect(
            code: String,
            now: Instant,
        ): ProviderConnection = ProviderConnection(providerCode, defaultProviderInstanceId, connected = true)

        override suspend fun sync(
            request: DomainProviderSyncRequest,
            now: Instant,
            progress: ProviderSyncProgressSink,
        ): ProviderSyncSummary = error("Sync is not supported")
    }

    private class BlockingProvider : HealthProvider {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val syncCalls = AtomicInteger(0)

        override val providerCode = "blocking_provider"
        override val defaultProviderInstanceId = "blocking-provider-me"
        override val descriptor = descriptorFor(providerCode, "Blocking Provider")

        override fun isConfigured(): Boolean = true

        override fun getAuthUrl(state: String): String = error("OAuth is not supported")

        override suspend fun connect(
            code: String,
            now: Instant,
        ): ProviderConnection = error("OAuth is not supported")

        override suspend fun sync(
            request: DomainProviderSyncRequest,
            now: Instant,
            progress: ProviderSyncProgressSink,
        ): ProviderSyncSummary {
            syncCalls.incrementAndGet()
            started.complete(Unit)
            release.await()
            return summaryFor(request)
        }
    }

    private class CountingProvider : HealthProvider {
        val syncCalls = AtomicInteger(0)

        override val providerCode = "counting_provider"
        override val defaultProviderInstanceId = "counting-provider-me"
        override val descriptor = descriptorFor(providerCode, "Counting Provider")

        override fun isConfigured(): Boolean = true

        override fun getAuthUrl(state: String): String = error("OAuth is not supported")

        override suspend fun connect(
            code: String,
            now: Instant,
        ): ProviderConnection = error("OAuth is not supported")

        override suspend fun sync(
            request: DomainProviderSyncRequest,
            now: Instant,
            progress: ProviderSyncProgressSink,
        ): ProviderSyncSummary {
            syncCalls.incrementAndGet()
            return summaryFor(request)
        }
    }

    private companion object {
        fun descriptorFor(
            providerCode: String,
            displayName: String,
        ) = HealthProviderDescriptor(
            providerCode = providerCode,
            displayName = displayName,
            authType = ProviderAuthType.NONE,
            requiresAuthentication = false,
            supportedDataTypes = listOf("steps"),
            defaultDataTypes = listOf("steps"),
            maxSyncRangeDays = 31,
            supportsPageSize = false,
            workflowEndpoints = ProviderWorkflowEndpoints(sync = "/sync"),
        )

        fun HealthProvider.summaryFor(request: DomainProviderSyncRequest): ProviderSyncSummary =
            ProviderSyncSummary(
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
