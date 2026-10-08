package me.aquitano.health.application

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.aquitano.health.api.dto.ProviderSyncJobStatusResponse
import me.aquitano.health.api.dto.ProviderSyncRequest
import me.aquitano.health.domain.HealthProvider
import me.aquitano.health.domain.NotFoundException
import me.aquitano.health.domain.SyncJobStatus
import me.aquitano.health.infrastructure.repositories.ProviderOAuthRepository
import me.aquitano.health.infrastructure.repositories.ProviderSyncJobRepository
import me.aquitano.health.infrastructure.repositories.ScheduledSyncRepository
import me.aquitano.health.infrastructure.time.UtcClock
import me.aquitano.health.test.BlockingProvider
import me.aquitano.health.test.FakeProvider
import me.aquitano.health.test.PostgresIntegrationTest
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProviderSyncJobServiceTest : PostgresIntegrationTest() {
    private val now = Instant.parse("2026-05-01T10:00:00Z")
    private val request =
        ProviderSyncRequest(
            from = "2026-05-01T00:00:00Z",
            to = "2026-05-08T00:00:00Z",
            dataTypes = listOf("steps"),
        )

    @Test
    fun concurrentCreateWithSameKeyLaunchesJobOnce() =
        runBlocking {
            val provider = BlockingProvider()
            val fixture = Fixture(provider)
            val key = "sync-job-concurrent-key"

            val jobIds =
                coroutineScope {
                    val first = async { fixture.service.create(provider.providerCode, request, now, key) }
                    val second = async { fixture.service.create(provider.providerCode, request, now, key) }
                    listOf(first, second).awaitAll()
                }.map { it.jobId }

            assertEquals(jobIds[0], jobIds[1], "duplicate key must resolve to the same job")

            provider.started.await()
            provider.release.complete(Unit)
            val terminal = fixture.awaitTerminal(provider.providerCode, jobIds[0])

            assertEquals(1, provider.syncCalls.get(), "provider must run exactly once for a duplicate key")
            assertEquals(SyncJobStatus.Processed, terminal.status)
        }

    @Test
    fun createWithoutKeyLaunchesEveryTime() =
        runBlocking {
            val provider = FakeProvider("fake_provider")
            val fixture = Fixture(provider)

            val first = fixture.service.create(provider.providerCode, request, now, idempotencyKey = null)
            val second = fixture.service.create(provider.providerCode, request, now, idempotencyKey = null)

            assertNotEquals(first.jobId, second.jobId)
            listOf(first.jobId, second.jobId).forEach { fixture.awaitTerminal(provider.providerCode, it) }
            assertEquals(2, provider.syncCalls.get())
        }

    @Test
    fun requeueInterruptedJobsRequeuesUntilRestartCap() =
        runBlocking {
            val repository = ProviderSyncJobRepository(openDatabase())
            val interruptedId = UUID.randomUUID().toString()
            val finishedId = UUID.randomUUID().toString()
            listOf(interruptedId, finishedId).forEach { id ->
                repository.create(
                    id = id,
                    providerCode = "fake_provider",
                    providerInstanceId = null,
                    requestedFrom = now,
                    requestedTo = now.plusSeconds(3600),
                    dataTypes = null,
                    pageSize = null,
                    now = now,
                )
            }
            repository.markRunning(finishedId, now)
            repository.finish(
                id = finishedId,
                status = "processed",
                batchesCount = 0,
                emptyCount = 0,
                errorCount = 0,
                summaryJson = null,
                errorMessage = null,
                now = now,
            )

            repeat(3) { attempt ->
                repository.markRunning(interruptedId, now)
                repository.markItemCompleted(interruptedId, "steps", now, now.plusSeconds(3600), now)
                val result = repository.requeueInterruptedJobs(now, maxRestarts = 3)
                assertEquals(listOf(interruptedId), result.resumed.map { it.id })
                assertTrue(result.abandoned.isEmpty())
                assertEquals(attempt + 1, result.resumed.single().restartCount)
                val requeued = repository.get(interruptedId)!!
                assertEquals("queued", requeued.status)
                assertEquals(0, requeued.completedItems)
            }

            repository.markRunning(interruptedId, now)
            val capped = repository.requeueInterruptedJobs(now, maxRestarts = 3)
            assertTrue(capped.resumed.isEmpty())
            assertEquals(listOf(interruptedId), capped.abandoned.map { it.id })

            val abandoned = repository.get(interruptedId)!!
            assertEquals("failed", abandoned.status)
            assertEquals(3, abandoned.restartCount)
            assertEquals("processed", repository.get(finishedId)!!.status)
        }

    @Test
    fun latestPrefersOlderRunningJobOverNewerFinishedJob() =
        runBlocking {
            val repository = ProviderSyncJobRepository(openDatabase())
            val runningId = UUID.randomUUID().toString()
            val finishedId = UUID.randomUUID().toString()
            listOf(runningId to now, finishedId to now.plusSeconds(60)).forEach { (id, createdAt) ->
                repository.create(
                    id = id,
                    providerCode = "fake_provider",
                    providerInstanceId = null,
                    requestedFrom = now,
                    requestedTo = now.plusSeconds(3600),
                    dataTypes = null,
                    pageSize = null,
                    now = createdAt,
                )
            }
            repository.markRunning(runningId, now)
            repository.finish(
                id = finishedId,
                status = "processed",
                batchesCount = 0,
                emptyCount = 0,
                errorCount = 0,
                summaryJson = null,
                errorMessage = null,
                now = now.plusSeconds(120),
            )

            assertEquals(runningId, repository.latest("fake_provider")?.id)

            repository.finish(
                id = runningId,
                status = "failed",
                batchesCount = 0,
                emptyCount = 0,
                errorCount = 1,
                summaryJson = null,
                errorMessage = "boom",
                now = now.plusSeconds(180),
            )

            assertEquals(finishedId, repository.latest("fake_provider")?.id)
        }

    @Test
    fun startResumesInterruptedJob() =
        runBlocking {
            val provider = FakeProvider("fake_provider")
            val fixture = Fixture(provider)
            val repository = ProviderSyncJobRepository(fixture.database)
            val jobId = UUID.randomUUID().toString()
            repository.create(
                id = jobId,
                providerCode = provider.providerCode,
                providerInstanceId = null,
                requestedFrom = now,
                requestedTo = now.plusSeconds(3600),
                dataTypes = listOf("steps"),
                pageSize = null,
                now = now,
            )
            repository.markRunning(jobId, now)

            fixture.service.start(now)
            val terminal = fixture.awaitTerminal(provider.providerCode, jobId)

            assertEquals(SyncJobStatus.Processed, terminal.status)
            assertEquals(1, terminal.restartCount)
            assertEquals(1, provider.syncCalls.get())
        }

    @Test
    fun getRejectsJobOfAnotherProvider(): Unit =
        runBlocking {
            val provider = FakeProvider("fake_provider")
            val other = FakeProvider("other_provider")
            val fixture = Fixture(provider, other)
            val job = fixture.service.create(provider.providerCode, request, now)

            assertFailsWith<NotFoundException> { fixture.service.get(other.providerCode, job.jobId) }
            fixture.awaitTerminal(provider.providerCode, job.jobId)
        }

    private inner class Fixture(
        vararg providers: HealthProvider,
    ) {
        val database = openDatabase()
        private val registry = HealthProviderRegistry(providers.toList())
        private val oAuthRepository = ProviderOAuthRepository(database)
        private val workflowService =
            ProviderWorkflowService(
                providerRegistry = registry,
                providerOAuthRepository = oAuthRepository,
                providerStatusService = ProviderStatusService(registry, oAuthRepository),
                scheduledSyncRepository = ScheduledSyncRepository(database),
            )
        val service =
            ProviderSyncJobService(
                providerRegistry = registry,
                workflowService = workflowService,
                repository = ProviderSyncJobRepository(database),
                clock = UtcClock(),
            )

        suspend fun awaitTerminal(
            providerCode: String,
            jobId: String,
        ): ProviderSyncJobStatusResponse =
            withTimeout(30_000) {
                var job = service.get(providerCode, jobId)
                while (!job.status.terminal) {
                    delay(50)
                    job = service.get(providerCode, jobId)
                }
                job
            }
    }
}
