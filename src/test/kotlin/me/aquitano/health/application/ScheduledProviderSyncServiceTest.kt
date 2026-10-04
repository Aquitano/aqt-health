package me.aquitano.health.application

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.aquitano.health.api.dto.ScheduledSyncConfigUpdateRequest
import me.aquitano.health.domain.ConflictException
import me.aquitano.health.domain.HealthProvider
import me.aquitano.health.domain.HealthProviderDescriptor
import me.aquitano.health.domain.ProviderAuthType
import me.aquitano.health.domain.ProviderConnection
import me.aquitano.health.domain.ProviderSyncProgressSink
import me.aquitano.health.domain.ProviderSyncRequest
import me.aquitano.health.domain.ProviderSyncSummary
import me.aquitano.health.domain.ProviderWorkflowEndpoints
import me.aquitano.health.infrastructure.repositories.ProviderOAuthRepository
import me.aquitano.health.infrastructure.repositories.ScheduledSyncRepository
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.PostgresTestDatabase
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduledProviderSyncServiceTest : PostgresIntegrationTest() {
    @Test
    fun maximumLookbackAdvancesCheckpointAndRefreshesHistory() =
        runBlocking {
            val provider = BlockingProvider().apply { release.complete(Unit) }
            val (service, repository) = serviceWith(provider)
            val now = Instant.parse("2026-05-31T10:00:00Z")
            repository.upsertConfig(
                provider.providerCode,
                provider.defaultProviderInstanceId,
                true,
                listOf("steps"),
                1_440,
                31,
                now,
                now,
            )
            repeat(3) { offset ->
                val runAt = now.plusSeconds(offset * 86_400L)
                assertEquals(1, service.runDue(runAt))
                val config = repository.getConfig(provider.providerCode, provider.defaultProviderInstanceId)!!
                assertEquals(runAt, repository.checkpoints(config.id).single().checkpointAt)
                assertTrue(provider.requests.last().refresh)
            }
            assertEquals(now.minusSeconds(31 * 86_400L), provider.requests[1].from)
            assertEquals(now.plusSeconds(86_400), provider.requests[1].to)
        }

    @Test
    fun longOutageCatchesUpInBoundedWindowsAtMaximumLookback() =
        runBlocking {
            val provider = BlockingProvider().apply { release.complete(Unit) }
            val (service, repository) = serviceWith(provider)
            val originalCheckpoint = Instant.parse("2026-01-01T10:00:00Z")
            val now = Instant.parse("2026-07-01T10:00:00Z")
            val config =
                repository.upsertConfig(
                    provider.providerCode,
                    provider.defaultProviderInstanceId,
                    true,
                    listOf("steps"),
                    1_440,
                    31,
                    now,
                    originalCheckpoint,
                )
            repository.markDataTypeSuccess(config.id, "steps", originalCheckpoint.minusSeconds(86_400), originalCheckpoint, originalCheckpoint)
            var checkpoint = originalCheckpoint
            repeat(6) {
                service.runNow(provider.providerCode, provider.defaultProviderInstanceId, now)
                val requested = provider.requests.last()
                assertEquals(checkpoint.minusSeconds(31 * 86_400L), requested.from)
                assertEquals(minOf(now, checkpoint.plusSeconds(31 * 86_400L)), requested.to)
                assertTrue(requested.to.isAfter(checkpoint))
                checkpoint = repository.checkpoints(config.id).single().checkpointAt!!
                assertEquals(requested.to, checkpoint)
            }
            assertEquals(now, checkpoint)
        }

    @Test
    fun manualRunConflictsWhileScheduledRunIsActiveForSameAccount() =
        runBlocking {
            val database = openDatabase(PostgresTestDatabase.config())
            val repository = ScheduledSyncRepository(database)
            val provider = BlockingProvider()
            val service =
                ScheduledProviderSyncService(
                    providerRegistry = HealthProviderRegistry(listOf(provider)),
                    providerOAuthRepository = ProviderOAuthRepository(database),
                    repository = repository,
                    runGuard = ScheduledSyncRunGuard(),
                )
            val now = Instant.parse("2026-05-31T10:00:00Z")

            repository.upsertConfig(
                providerCode = provider.providerCode,
                providerInstanceId = provider.defaultProviderInstanceId,
                enabled = true,
                dataTypes = listOf("steps"),
                cadenceMinutes = 1_440,
                lookbackDays = 7,
                nextRunAt = now,
                now = now,
            )

            coroutineScope {
                val scheduledRun = async { service.runDue(now) }
                provider.started.await()

                val conflict =
                    assertFailsWith<ConflictException> {
                        service.runNow(provider.providerCode, provider.defaultProviderInstanceId, now)
                    }

                provider.release.complete(Unit)
                assertEquals("scheduled_sync_already_running", conflict.code)
                assertEquals(1, scheduledRun.await())
                assertEquals(1, provider.syncCalls.get())
            }
        }

    @Test
    fun nonRetryableFailureParksConfigOnlyAfterRepeatedFailures() =
        runBlocking {
            val provider = ThrowingProvider(ConflictException("withings_account_not_found", "account is gone"))
            val (service, repository) = serviceWith(provider)
            var runAt = Instant.parse("2026-05-31T10:00:00Z")
            configureEnabled(repository, provider, runAt)

            repeat(2) {
                assertEquals(1, service.runDue(runAt))
                runAt = assertNotNull(repository.getConfig(provider.providerCode, provider.defaultProviderInstanceId)?.nextRunAt)
            }
            assertEquals(1, service.runDue(runAt))

            val config = repository.getConfig(provider.providerCode, provider.defaultProviderInstanceId)
            assertNotNull(config)
            assertNull(config.nextRunAt)
            assertEquals(3, config.failureCount)
        }

    @Test
    fun reschedulingParkedOrPausedConfigStartsFailureCountOver() =
        runBlocking {
            val provider = ThrowingProvider(ConflictException("withings_account_not_found", "account is gone"))
            val database = openDatabase(PostgresTestDatabase.config())
            val now = Instant.parse("2026-05-31T10:00:00Z")
            ProviderOAuthRepository(database).upsertAccount(
                providerCode = provider.providerCode,
                providerUserId = "throwing-user",
                providerInstanceId = provider.defaultProviderInstanceId,
                accessTokenCiphertext = "access",
                refreshTokenCiphertext = "refresh",
                tokenType = "Bearer",
                expiresAt = now.plusSeconds(3600),
                scope = "scope",
                now = now,
            )
            val (service, repository) = serviceWith(provider, database)

            for (wasEnabled in listOf(true, false)) {
                val stopped =
                    repository.upsertConfig(
                        providerCode = provider.providerCode,
                        providerInstanceId = provider.defaultProviderInstanceId,
                        enabled = wasEnabled,
                        dataTypes = listOf("steps"),
                        cadenceMinutes = 1_440,
                        lookbackDays = 7,
                        nextRunAt = null,
                        now = now,
                    )
                repository.markFailure(stopped.id, failureCount = 3, nextRunAt = null, errorMessage = "account is gone", now = now)

                service.updateConfig(
                    provider.providerCode,
                    provider.defaultProviderInstanceId,
                    ScheduledSyncConfigUpdateRequest(enabled = true),
                    now,
                )

                val rescheduled = repository.getConfig(provider.providerCode, provider.defaultProviderInstanceId)
                assertNotNull(rescheduled)
                assertEquals(now, rescheduled.nextRunAt)
                assertEquals(0, rescheduled.failureCount, "wasEnabled=$wasEnabled")
            }
        }

    @Test
    fun needsReauthAccountParksConfigOnFirstRetryableFailure() =
        runBlocking {
            val provider = ThrowingProvider(IllegalStateException("upstream timed out"))
            val database = openDatabase(PostgresTestDatabase.config())
            val now = Instant.parse("2026-05-31T10:00:00Z")
            val accounts = ProviderOAuthRepository(database)
            accounts.upsertAccount(
                providerCode = provider.providerCode,
                providerUserId = "throwing-user",
                providerInstanceId = provider.defaultProviderInstanceId,
                accessTokenCiphertext = "access",
                refreshTokenCiphertext = "refresh",
                tokenType = "Bearer",
                expiresAt = now.plusSeconds(3600),
                scope = "scope",
                now = now,
            )
            val account = accounts.accountByProviderInstanceForStatus(provider.providerCode, provider.defaultProviderInstanceId)!!
            accounts.markNeedsReauth(account.id, account.refreshTokenCiphertext, "withings_needs_reauth", "reconnect", now)
            val (service, repository) = serviceWith(provider, database)
            configureEnabled(repository, provider, now)

            assertEquals(1, service.runDue(now))

            val config = repository.getConfig(provider.providerCode, provider.defaultProviderInstanceId)
            assertNotNull(config)
            assertNull(config.nextRunAt)
            assertEquals(1, config.failureCount)
        }

    @Test
    fun transientFailureKeepsRetryingEvenWhenMessageMentionsValidation() =
        runBlocking {
            val provider =
                ThrowingProvider(
                    IllegalStateException("upstream response failed schema validation, not connected to peer"),
                )
            val (service, repository) = serviceWith(provider)
            val now = Instant.parse("2026-05-31T10:00:00Z")
            configureEnabled(repository, provider, now)

            assertEquals(1, service.runDue(now))

            val config = repository.getConfig(provider.providerCode, provider.defaultProviderInstanceId)
            assertNotNull(config)
            assertEquals(ScheduledSyncPolicy.nextRunAfterFailure(now, 1), config.nextRunAt)
            assertEquals(1, config.failureCount)
        }

    private fun serviceWith(
        provider: HealthProvider,
        database: Database = openDatabase(PostgresTestDatabase.config()),
    ): Pair<ScheduledProviderSyncService, ScheduledSyncRepository> {
        val repository = ScheduledSyncRepository(database)
        val service =
            ScheduledProviderSyncService(
                providerRegistry = HealthProviderRegistry(listOf(provider)),
                providerOAuthRepository = ProviderOAuthRepository(database),
                repository = repository,
                runGuard = ScheduledSyncRunGuard(),
            )
        return service to repository
    }

    private suspend fun configureEnabled(
        repository: ScheduledSyncRepository,
        provider: HealthProvider,
        now: Instant,
    ) {
        repository.upsertConfig(
            providerCode = provider.providerCode,
            providerInstanceId = provider.defaultProviderInstanceId,
            enabled = true,
            dataTypes = listOf("steps"),
            cadenceMinutes = 1_440,
            lookbackDays = 7,
            nextRunAt = now,
            now = now,
        )
    }

    private class ThrowingProvider(
        private val failure: Exception,
    ) : HealthProvider {
        override val providerCode = "throwing_provider"
        override val defaultProviderInstanceId = "throwing-provider-me"
        override val descriptor =
            HealthProviderDescriptor(
                providerCode = providerCode,
                displayName = "Throwing Provider",
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
        ): ProviderSyncSummary = throw failure
    }

    private class BlockingProvider : HealthProvider {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val syncCalls = AtomicInteger(0)
        val requests = mutableListOf<ProviderSyncRequest>()

        override val providerCode = "blocking_provider"
        override val defaultProviderInstanceId = "blocking-provider-me"
        override val descriptor =
            HealthProviderDescriptor(
                providerCode = providerCode,
                displayName = "Blocking Provider",
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
            started.complete(Unit)
            release.await()
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
}
