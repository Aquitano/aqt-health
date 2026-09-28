package me.aquitano.health.application

import me.aquitano.health.test.PostgresIntegrationTest
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import me.aquitano.health.api.dto.ActivitySummary
import me.aquitano.health.api.dto.ScalarSample
import me.aquitano.health.api.dto.IngestionBatchRequest
import me.aquitano.health.domain.ReplayJobStatus
import me.aquitano.health.api.dto.ReplayJobStatusResponse
import me.aquitano.health.api.dto.ReplayRequest
import me.aquitano.health.api.dto.SleepSession
import me.aquitano.health.api.dto.StepInterval
import me.aquitano.health.test.metricWriteService
import me.aquitano.health.domain.DerivedKind
import me.aquitano.health.domain.RecordTypes
import me.aquitano.health.domain.RequestValidationException
import me.aquitano.health.infrastructure.config.DatabaseConfig
import me.aquitano.health.infrastructure.repositories.IngestionRepository
import me.aquitano.health.infrastructure.repositories.PendingDerivedRebuildRepository
import me.aquitano.health.infrastructure.repositories.ProjectionWipeRepository
import me.aquitano.health.infrastructure.repositories.ReplayJobRepository
import me.aquitano.health.infrastructure.repositories.SupportRepository
import me.aquitano.health.infrastructure.time.UtcClock
import me.aquitano.health.test.PostgresTestDatabase
import me.aquitano.health.test.derivedRebuildRegistry
import me.aquitano.health.test.realDerivedRebuildExecutor
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReplayServiceTest : PostgresIntegrationTest() {
    @Test
    fun wipeRepreparesWhenIngestionCommitsAfterTheSnapshotWasRead() = runBlocking {
        val fixture = Fixture(poolSize = 3)
        fixture.ingestMixedBatch()
        val jobId = PostgresTestDatabase.connection(fixture.dbConfig).use { blocker ->
            blocker.autoCommit = false
            blocker.createStatement().use { it.execute("LOCK TABLE scalar_samples IN SHARE MODE") }
            val ingestion = async { fixture.ingestAdditionalScalar() }
            try {
                withTimeout(10_000) {
                    while (fixture.waitingLocks("scalar_samples", "RowExclusiveLock") == 0) delay(10)
                }
                val start = fixture.replayService.create(
                    ReplayRequest(scope = "projections", metricTypes = listOf(RecordTypes.SCALAR), wipe = true),
                    fixture.clock.now(),
                )
                withTimeout(10_000) {
                    while (fixture.waitingLocks("ingestion_records", "ShareRowExclusiveLock") == 0) delay(10)
                }
                // Ingestion owns its raw-log write lock but has not committed. Replay has
                // prepared the old committed records and is waiting for that ingestion.
                blocker.rollback()
                ingestion.await()
                start.jobId
            } finally {
                blocker.rollback()
            }
        }
        val job = fixture.awaitReplay(jobId)
        assertEquals(ReplayJobStatus.Completed, job.status)
        assertEquals(2, job.recordsReplayed)
        assertEquals(2, fixture.count("scalar_samples"))
        assertEquals(1, fixture.singleInt("SELECT COUNT(*) FROM scalar_samples WHERE provider_record_id = 'hr-late'"))
    }

    @Test
    fun replayRestoresWipedProjectionsAndDerivedTables() = runBlocking {
        val fixture = Fixture()
        fixture.ingestMixedBatch()

        assertEquals(1, fixture.count("scalar_samples"))
        assertEquals(1, fixture.count("canonical_scalar_samples"))
        fixture.execute("DELETE FROM scalar_samples")
        assertEquals(0, fixture.count("scalar_samples"))
        assertEquals(0, fixture.count("canonical_scalar_samples"))

        val job = fixture.runReplay(ReplayRequest(scope = "all"))

        assertEquals(ReplayJobStatus.Completed, job.status)
        assertTrue(job.metricsWritten >= 1, "expected restored metrics, got ${job.metricsWritten}")
        assertEquals(0, job.mappingFailures)
        assertEquals(1, fixture.count("scalar_samples"))
        assertEquals(1, fixture.count("canonical_scalar_samples"))
        assertEquals(1, fixture.count("step_samples"))
        assertEquals(1, fixture.count("canonical_activity_summaries"))
    }

    @Test
    fun verifyModeReportsNoMissingWritesOnIntactData() = runBlocking {
        val fixture = Fixture()
        fixture.ingestMixedBatch()

        val job = fixture.runReplay(ReplayRequest(scope = "projections"))

        assertEquals(ReplayJobStatus.Completed, job.status)
        assertEquals(0, job.metricsWritten)
        assertTrue(job.duplicatesSkipped >= 4, "expected duplicates, got ${job.duplicatesSkipped}")
        assertEquals(0, job.mappingFailures)
    }

    @Test
    fun derivedOnlyReplayRebuildsDerivedTablesWithoutTouchingProjections() = runBlocking {
        val fixture = Fixture()
        fixture.ingestMixedBatch()

        fixture.execute("DELETE FROM step_daily_summaries")

        val job = fixture.runReplay(ReplayRequest(scope = "derived"))

        assertEquals(ReplayJobStatus.Completed, job.status)
        assertEquals(0, job.recordsReplayed)
        assertEquals(1, fixture.count("step_daily_summaries"))
        assertEquals(1, fixture.count("canonical_step_samples"))
        assertEquals(1, fixture.count("canonical_activity_summaries"))
    }

    @Test
    fun wipeReplayRewritesProjectionRowsInRange() = runBlocking {
        val fixture = Fixture()
        fixture.ingestMixedBatch()
        val originalId = fixture.singleInt("SELECT id FROM scalar_samples")

        val job = fixture.runReplay(
            ReplayRequest(
                scope = "all",
                metricTypes = listOf(RecordTypes.SCALAR),
                fromDate = "2026-04-19",
                toDate = "2026-04-19",
                wipe = true,
            )
        )

        assertEquals(ReplayJobStatus.Completed, job.status)
        assertEquals(1, job.recordsReplayed)
        assertEquals(1, job.metricsWritten)
        assertEquals(1, fixture.count("scalar_samples"))
        assertEquals(1, fixture.count("canonical_scalar_samples"))
        assertTrue(
            fixture.singleInt("SELECT id FROM scalar_samples") != originalId,
            "wipe should rewrite the row under a new id",
        )
        // Untouched record types survive a scoped wipe.
        assertEquals(1, fixture.count("step_samples"))
    }

    @Test
    fun replayOverDateRangeRebuildsEveryDerivedKind() = runBlocking {
        val fixture = Fixture()
        fixture.ingestMixedBatch()

        // The fixture batch contains one record per derived kind; the shared registry mapping
        // must route each of them to a rebuild, so no kind can drift out of the replay path.
        fixture.execute("DELETE FROM step_daily_summaries")

        val job = fixture.runReplay(
            ReplayRequest(scope = "derived", fromDate = "2026-04-18", toDate = "2026-04-19")
        )

        assertEquals(ReplayJobStatus.Completed, job.status)
        assertEquals(1, fixture.count("step_daily_summaries"))
    }

    @Test
    fun sharedAffectedDatesMappingCoversEveryDerivedKind() {
        val registry = derivedRebuildRegistry()
        val coveredKinds = listOf(
            Triple(
                RecordTypes.STEP_INTERVAL,
                Instant.parse("2026-04-19T08:00:00Z"),
                Instant.parse("2026-04-19T09:00:00Z"),
            ),
            Triple(
                RecordTypes.SLEEP_SESSION,
                Instant.parse("2026-04-18T22:00:00Z"),
                Instant.parse("2026-04-19T06:00:00Z"),
            ),
        ).flatMap { (recordType, startAt, endAt) ->
            registry.affectedDatesFor(recordType, startAt, endAt).keys
        }.toSet()

        assertEquals(
            DerivedKind.entries.toSet(),
            coveredKinds,
            "every DerivedKind must be reachable from a replayable record type; " +
                "extend this test's record list when adding a kind",
        )
    }

    @Test
    fun repositoryCreateFlagsDuplicateIdempotencyKey() = runBlocking {
        val repository = ReplayJobRepository(Fixture().database)
        val key = "replay-repo-flag-key"
        val id1 = java.util.UUID.randomUUID().toString()
        val id2 = java.util.UUID.randomUUID().toString()

        val first = repository.create(
            id = id1,
            scope = "projections",
            metricTypes = null,
            fromDate = null,
            toDate = null,
            wipe = false,
            now = Instant.parse("2026-05-01T10:00:00Z"),
            idempotencyKey = key,
            idempotencyRequestHash = "hash-a",
        )
        val second = repository.create(
            id = id2,
            scope = "projections",
            metricTypes = null,
            fromDate = null,
            toDate = null,
            wipe = false,
            now = Instant.parse("2026-05-01T10:00:00Z"),
            idempotencyKey = key,
            idempotencyRequestHash = "hash-a",
        )

        assertTrue(first.created)
        assertTrue(!second.created)
        assertEquals(id1, second.record.id)
    }

    @Test
    fun replayRejectsUnknownScopeAndRecordTypes(): Unit = runBlocking {
        val fixture = Fixture()
        assertFailsWith<RequestValidationException> {
            fixture.replayService.create(ReplayRequest(scope = "bogus"), fixture.clock.now())
        }
        assertFailsWith<RequestValidationException> {
            fixture.replayService.create(
                ReplayRequest(metricTypes = listOf("not_a_record_type")),
                fixture.clock.now(),
            )
        }
        assertFailsWith<RequestValidationException> {
            fixture.replayService.create(
                ReplayRequest(scope = "derived", wipe = true),
                fixture.clock.now(),
            )
        }
    }

    private inner class Fixture(poolSize: Int = 1) {
        val dbConfig: DatabaseConfig = PostgresTestDatabase.config().copy(maxPoolSize = poolSize)
        val database: Database = openDatabase(dbConfig)
        val clock = UtcClock()
        private val mappingService = IngestionMappingService()
        private val metricWriteService = metricWriteService()
        private val derivedRebuildExecutor = realDerivedRebuildExecutor(database)
        private val ingestionService = IngestionService(
            database = database,
            mappingService = mappingService,
            supportRepository = SupportRepository(database),
            ingestionRepository = IngestionRepository(),
            metricWriteService = metricWriteService,
            derivedRebuildExecutor = derivedRebuildExecutor,
            pendingDerivedRebuildRepository = PendingDerivedRebuildRepository(database),
        )
        val replayService = ReplayService(
            database = database,
            ingestionRepository = IngestionRepository(),
            mappingService = mappingService,
            metricWriteService = metricWriteService,
            derivedRebuildExecutor = derivedRebuildExecutor,
            derivedRebuildRegistry = derivedRebuildRegistry(),
            pendingDerivedRebuildRepository = PendingDerivedRebuildRepository(database),
            replayJobRepository = ReplayJobRepository(database),
            projectionWipeRepository = ProjectionWipeRepository(),
            clock = clock,
        )

        suspend fun ingestMixedBatch() {
            ingestionService.ingestBatch(
                IngestionBatchRequest(
                    provider = "withings",
                    providerInstanceId = "scale-1",
                    batchExternalId = "replay-fixture-1",
                    ingestedAt = "2026-04-19T10:00:00Z",
                    sourcePayload = buildJsonObject {},
                    records = listOf(
                        StepInterval(
                            providerRecordId = "steps-1",
                            startAt = "2026-04-19T08:00:00Z",
                            endAt = "2026-04-19T09:00:00Z",
                            steps = 1200,
                        ),
                        ScalarSample(
                            providerRecordId = "hr-1",
                            measuredAt = "2026-04-19T08:30:00Z",
                            metricType = "heart_rate",
                            value = 64.0,
                            context = "resting",
                        ),
                        SleepSession(
                            providerRecordId = "sleep-1",
                            startAt = "2026-04-18T22:00:00Z",
                            endAt = "2026-04-19T06:00:00Z",
                        ),
                        ActivitySummary(
                            providerRecordId = "activity-1",
                            date = "2026-04-19",
                            distanceMeters = 4200.0,
                            activeMinutes = 55,
                        ),
                    ),
                ),
                Instant.parse("2026-04-19T10:01:00Z"),
            )
        }

        suspend fun ingestAdditionalScalar() {
            ingestionService.ingestBatch(
                IngestionBatchRequest(
                    provider = "withings", providerInstanceId = "scale-1",
                    ingestedAt = "2026-04-19T10:02:00Z", sourcePayload = buildJsonObject {},
                    records = listOf(ScalarSample(
                        providerRecordId = "hr-late", measuredAt = "2026-04-19T08:31:00Z",
                        metricType = "heart_rate", value = 65.0, context = "resting",
                    )),
                ),
                Instant.parse("2026-04-19T10:02:00Z"),
            )
        }

        suspend fun runReplay(request: ReplayRequest): ReplayJobStatusResponse {
            val start = replayService.create(request, clock.now())
            return awaitReplay(start.jobId)
        }

        suspend fun awaitReplay(jobId: String): ReplayJobStatusResponse =
            withTimeout(60_000) {
                var job = replayService.get(jobId)
                while (!job.status.terminal) {
                    delay(100)
                    job = replayService.get(jobId)
                }
                job
            }

        fun waitingLocks(table: String, mode: String): Int = singleInt(
            "SELECT COUNT(*) FROM pg_locks WHERE relation = '$table'::regclass AND mode = '$mode' AND NOT granted"
        )

        fun count(table: String): Int = singleInt("SELECT COUNT(*) FROM $table")

        fun singleInt(sql: String): Int =
            PostgresTestDatabase.connection(dbConfig).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { resultSet ->
                        resultSet.next()
                        resultSet.getInt(1)
                    }
                }
            }

        fun execute(sql: String) {
            PostgresTestDatabase.connection(dbConfig).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(sql)
                }
            }
        }
    }
}
