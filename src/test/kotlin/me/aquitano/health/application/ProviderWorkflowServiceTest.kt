package me.aquitano.health.application

import kotlinx.coroutines.runBlocking
import me.aquitano.health.api.dto.ProviderSyncRequest
import me.aquitano.health.domain.ProviderConnection
import me.aquitano.health.domain.RequestValidationException
import me.aquitano.health.domain.ValidationIssueCodes
import me.aquitano.health.infrastructure.repositories.ProviderOAuthRepository
import me.aquitano.health.infrastructure.repositories.ScheduledSyncRepository
import me.aquitano.health.test.FakeProvider
import me.aquitano.health.test.PostgresIntegrationTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProviderWorkflowServiceTest : PostgresIntegrationTest() {
    private val now = Instant.parse("2026-05-01T10:00:00Z")

    private fun request(
        from: String = "2026-05-01T00:00:00Z",
        to: String = "2026-05-08T00:00:00Z",
    ) = ProviderSyncRequest(from = from, to = to, dataTypes = listOf("steps"))

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
            val database = openDatabase()
            val oAuthRepository = ProviderOAuthRepository(database)
            val scheduledSyncRepository = ScheduledSyncRepository(database)
            val registry = HealthProviderRegistry(listOf(provider))
            val service =
                ProviderWorkflowService(
                    providerRegistry = registry,
                    providerOAuthRepository = oAuthRepository,
                    providerStatusService = ProviderStatusService(registry, oAuthRepository),
                    scheduledSyncRepository = scheduledSyncRepository,
                )
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
            oAuthRepository.insertState("reconnect-state", provider.providerCode, now, now.plusSeconds(600))

            service.completeOAuth(provider.providerCode, "code", "reconnect-state", null, now)

            val resumed = scheduledSyncRepository.getConfig(provider.providerCode, provider.defaultProviderInstanceId)!!
            assertEquals(now, resumed.nextRunAt)
            assertEquals(0, resumed.failureCount)
        }

    private class ConnectingProvider : FakeProvider("connecting_provider") {
        override suspend fun connect(
            code: String,
            now: Instant,
        ): ProviderConnection = ProviderConnection(providerCode, defaultProviderInstanceId, connected = true)
    }
}
