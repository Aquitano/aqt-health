package me.aquitano.health.application

import kotlinx.coroutines.runBlocking
import me.aquitano.health.infrastructure.database.suspendDbTransaction
import me.aquitano.health.infrastructure.repositories.PendingDerivedRebuildRepository
import me.aquitano.health.infrastructure.repositories.SupportRepository
import me.aquitano.health.infrastructure.time.UtcClock
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.PostgresTestDatabase
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class PendingDerivedRebuildSweeperTest : PostgresIntegrationTest() {
    @Test
    fun failingDateDoesNotDelayOtherDatesOrSources() =
        runBlocking {
            val database = openDatabase(PostgresTestDatabase.config())
            val repository = PendingDerivedRebuildRepository(database)
            val now = Instant.parse("2026-06-01T10:00:00Z")
            val failingDate = LocalDate.parse("2026-05-30")
            val healthyDate = failingDate.plusDays(1)
            val sources =
                suspendDbTransaction(db = database) {
                    val support = SupportRepository(database)
                    listOf("first", "second")
                        .map { instance ->
                            support.resolveOrCreateSourceInstanceInTransaction("health_connect", instance, now).id
                        }.also { sourceIds ->
                            repository.enqueueInTransaction(
                                DerivedRebuildRequest(sourceIds.first(), setOf(failingDate)),
                                now = now.minusSeconds(1),
                            )
                            sourceIds.forEach { sourceId ->
                                repository.enqueueInTransaction(
                                    DerivedRebuildRequest(sourceId, setOf(healthyDate)),
                                    now = now,
                                )
                            }
                        }
                }
            val rebuilt = mutableListOf<DerivedRebuildRequest>()
            val executor =
                object : DerivedRebuildExecutor {
                    override suspend fun rebuild(
                        requests: List<DerivedRebuildRequest>,
                        computedAt: Instant,
                    ) {
                        check(requests.none { failingDate in it.affectedStepDates }) { "Date cannot be rebuilt" }
                        rebuilt += requests
                    }
                }
            val sweeper = PendingDerivedRebuildSweeper(repository, executor, UtcClock())

            assertEquals(2, sweeper.sweep(now))
            assertEquals(sources.toSet(), rebuilt.map { it.sourceInstanceId }.toSet())
            assertEquals(setOf(healthyDate), rebuilt.flatMap { it.affectedStepDates }.toSet())
            val remaining = repository.due(now.plusSeconds(60), 10).single()
            assertEquals(failingDate, remaining.affectedDate)
            assertEquals(1, remaining.attempts)
        }

    @Test
    fun staleWorkerCannotAcknowledgeOrDelayNewerWork() =
        runBlocking {
            val database = openDatabase(PostgresTestDatabase.config())
            val repository = PendingDerivedRebuildRepository(database)
            val now = Instant.parse("2026-06-01T10:00:00Z")
            val source =
                suspendDbTransaction(db = database) {
                    SupportRepository(database).resolveOrCreateSourceInstanceInTransaction("health_connect", "revisions", now).id
                }
            val request = DerivedRebuildRequest(source, setOf(LocalDate.parse("2026-06-01")))
            val old = suspendDbTransaction(db = database) { repository.enqueueInTransaction(request, now = now) }
            val fresh = suspendDbTransaction(db = database) { repository.enqueueInTransaction(request, now = now) }
            repository.deleteCompleted(old)
            repository.markAttemptFailed(old, { now.plusSeconds(3600) }, "stale failure", now)
            assertEquals(fresh, repository.due(now, 10))
            repository.deleteCompleted(fresh)
            assertEquals(emptyList(), repository.due(now, 10))
        }

    @Test
    fun retriesQueuedRebuildWithBackoffUntilItSucceeds() =
        runBlocking {
            val database = openDatabase(PostgresTestDatabase.config())
            val repository = PendingDerivedRebuildRepository(database)
            val supportRepository = SupportRepository(database)
            val executor = FlakyDerivedRebuildExecutor(failuresBeforeSuccess = 1)
            val sweeper =
                PendingDerivedRebuildSweeper(
                    repository = repository,
                    derivedRebuildExecutor = executor,
                    clock = UtcClock(),
                )
            val now = Instant.parse("2026-06-01T10:00:00Z")
            val date = LocalDate.parse("2026-05-31")

            val sourceInstanceId =
                suspendDbTransaction(db = database) {
                    val sourceInstance =
                        supportRepository.resolveOrCreateSourceInstanceInTransaction(
                            provider = "health_connect",
                            providerInstanceId = "pixel-8-health-connect",
                            now = now,
                        )
                    repository.enqueueInTransaction(
                        DerivedRebuildRequest(
                            sourceInstanceId = sourceInstance.id,
                            affectedStepDates = setOf(date),
                        ),
                        now = now,
                    )
                    sourceInstance.id
                }

            // First sweep: the executor still fails, so the row stays queued with backoff.
            assertEquals(0, sweeper.sweep(now))
            assertEquals(1, executor.calls.get())
            assertEquals(0, repository.due(now, limit = 10).size)

            // Not due again until the backoff window has elapsed.
            val afterBackoff = PendingDerivedRebuildPolicy.nextAttemptAfterFailure(now, attempts = 1)
            val due = repository.due(afterBackoff, limit = 10)
            assertEquals(1, due.size)
            assertEquals(sourceInstanceId, due.single().sourceInstanceId)
            assertEquals(1, due.single().attempts)
            assertEquals("flaky rebuild failure", due.single().lastErrorMessage)

            // Second sweep succeeds and clears the queue.
            assertEquals(1, sweeper.sweep(afterBackoff))
            assertEquals(2, executor.calls.get())
            assertEquals(0, repository.due(afterBackoff.plusSeconds(3_600), limit = 10).size)
            assertEquals(
                setOf(date),
                executor.lastRequest?.affectedStepDates,
            )
        }

    private class FlakyDerivedRebuildExecutor(
        private val failuresBeforeSuccess: Int,
    ) : DerivedRebuildExecutor {
        val calls = AtomicInteger(0)
        var lastRequest: DerivedRebuildRequest? = null

        override suspend fun rebuild(
            requests: List<DerivedRebuildRequest>,
            computedAt: Instant,
        ) {
            lastRequest = requests.single()
            if (calls.incrementAndGet() <= failuresBeforeSuccess) {
                throw IllegalStateException("flaky rebuild failure")
            }
        }
    }
}
