package me.aquitano.health.application

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import me.aquitano.health.api.dto.ActivitySummary
import me.aquitano.health.api.dto.IngestionBatchRequest
import me.aquitano.health.api.dto.ReplayJobStatusResponse
import me.aquitano.health.api.dto.ReplayRequest
import me.aquitano.health.api.dto.ScalarSample
import me.aquitano.health.api.dto.SleepSession
import me.aquitano.health.api.dto.StepInterval
import me.aquitano.health.domain.RecordTypes
import me.aquitano.health.domain.ReplayJobStatus
import me.aquitano.health.domain.ReplayScope
import me.aquitano.health.domain.RequestValidationException
import me.aquitano.health.infrastructure.config.DatabaseConfig
import me.aquitano.health.infrastructure.repositories.IngestionRepository
import me.aquitano.health.infrastructure.repositories.PendingDerivedRebuildRepository
import me.aquitano.health.infrastructure.repositories.ProjectionWipeRepository
import me.aquitano.health.infrastructure.repositories.ReplayJobRepository
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.PostgresTestDatabase
import me.aquitano.health.test.countRows
import me.aquitano.health.test.execute
import me.aquitano.health.test.ingestionService
import me.aquitano.health.test.metricWriteService
import me.aquitano.health.test.queryInt
import me.aquitano.health.test.queryString
import me.aquitano.health.test.realDerivedRebuildExecutor
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReplayServiceTest : PostgresIntegrationTest() {
    @Test
    fun wipeRepreparesWhenIngestionCommitsAfterTheSnapshotWasRead() =
        runBlocking {
            val fixture = Fixture(poolSize = 3)
            fixture.ingestMixedBatch()
            val jobId =
                PostgresTestDatabase.connection(fixture.config).use { blocker ->
                    blocker.autoCommit = false
                    blocker.createStatement().use { it.execute("LOCK TABLE scalar_samples IN SHARE MODE") }
                    val ingestion = async { fixture.ingestScalar("hr-late", "2026-04-19T08:31:00Z") }
                    try {
                        withTimeout(10_000) {
                            while (fixture.waitingLocks("scalar_samples", "RowExclusiveLock") == 0) delay(10)
                        }
                        val start =
                            fixture.replayService.create(
                                ReplayRequest(scope = ReplayScope.Projections, metricTypes = listOf(RecordTypes.SCALAR), wipe = true),
                                fixture.clock.instant(),
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
            assertEquals(2, fixture.config.countRows("scalar_samples"))
            assertEquals(1, fixture.config.queryInt("SELECT COUNT(*) FROM scalar_samples WHERE provider_record_id = 'hr-late'"))
        }

    @Test
    fun replayRestoresWipedProjectionsAndDerivedTables() =
        runBlocking {
            val fixture = Fixture()
            fixture.ingestMixedBatch()

            assertEquals(1, fixture.config.countRows("scalar_samples"))
            assertEquals(1, fixture.config.countRows("canonical_scalar_samples"))
            fixture.config.execute("DELETE FROM scalar_samples")
            assertEquals(0, fixture.config.countRows("scalar_samples"))
            assertEquals(0, fixture.config.countRows("canonical_scalar_samples"))

            val job = fixture.runReplay(ReplayRequest())

            assertEquals(ReplayJobStatus.Completed, job.status)
            assertTrue(job.metricsWritten >= 1, "expected restored metrics, got ${job.metricsWritten}")
            assertEquals(0, job.mappingFailures)
            assertEquals(1, fixture.config.countRows("scalar_samples"))
            assertEquals(1, fixture.config.countRows("canonical_scalar_samples"))
            assertEquals(1, fixture.config.countRows("step_samples"))
            assertEquals(1, fixture.config.countRows("canonical_activity_summaries"))
        }

    @Test
    fun verifyModeReportsNoMissingWritesOnIntactData() =
        runBlocking {
            val fixture = Fixture()
            fixture.ingestMixedBatch()

            val job = fixture.runReplay(ReplayRequest(scope = ReplayScope.Projections))

            assertEquals(ReplayJobStatus.Completed, job.status)
            assertEquals(0, job.metricsWritten)
            assertTrue(job.duplicatesSkipped >= 4, "expected duplicates, got ${job.duplicatesSkipped}")
            assertEquals(0, job.mappingFailures)
        }

    @Test
    fun derivedOnlyReplayRebuildsDerivedTablesWithoutTouchingProjections() =
        runBlocking {
            val fixture = Fixture()
            fixture.ingestMixedBatch()

            fixture.config.execute("DELETE FROM canonical_step_samples")

            val job = fixture.runReplay(ReplayRequest(scope = ReplayScope.Derived))

            assertEquals(ReplayJobStatus.Completed, job.status)
            assertEquals(0, job.recordsReplayed)
            assertEquals(1, fixture.config.countRows("canonical_step_samples"))
            assertEquals(1, fixture.config.countRows("canonical_activity_summaries"))
        }

    @Test
    fun wipeReplayRewritesProjectionRowsInRange() =
        runBlocking {
            val fixture = Fixture()
            fixture.ingestMixedBatch()
            val originalId = fixture.config.queryInt("SELECT id FROM scalar_samples")

            val job =
                fixture.runReplay(
                    ReplayRequest(
                        metricTypes = listOf(RecordTypes.SCALAR),
                        fromDate = "2026-04-19",
                        toDate = "2026-04-19",
                        wipe = true,
                    ),
                )

            assertEquals(ReplayJobStatus.Completed, job.status)
            assertEquals(1, job.recordsReplayed)
            assertEquals(1, job.metricsWritten)
            assertEquals(1, fixture.config.countRows("scalar_samples"))
            assertEquals(1, fixture.config.countRows("canonical_scalar_samples"))
            assertTrue(
                fixture.config.queryInt("SELECT id FROM scalar_samples") != originalId,
                "wipe should rewrite the row under a new id",
            )
            // Untouched record types survive a scoped wipe.
            assertEquals(1, fixture.config.countRows("step_samples"))
        }

    @Test
    fun dateRangeLimitsReplayToRecordsInsideIt() =
        runBlocking {
            val fixture = Fixture()
            fixture.ingestMixedBatch()
            fixture.ingestScalar("hr-in-range", "2026-04-21T08:00:00Z")
            fixture.config.execute("DELETE FROM scalar_samples")

            val job = fixture.runReplay(ReplayRequest(scope = ReplayScope.Projections, fromDate = "2026-04-21", toDate = "2026-04-21"))

            assertEquals(ReplayJobStatus.Completed, job.status)
            assertEquals(1, job.recordsReplayed)
            assertEquals("hr-in-range", fixture.config.queryString("SELECT string_agg(provider_record_id, ',') FROM scalar_samples"))
        }

    @Test
    fun replayRejectsUnknownRecordTypesAndDerivedWipe(): Unit =
        runBlocking {
            val fixture = Fixture()
            assertFailsWith<RequestValidationException> {
                fixture.replayService.create(
                    ReplayRequest(metricTypes = listOf("not_a_record_type")),
                    fixture.clock.instant(),
                )
            }
            assertFailsWith<RequestValidationException> {
                fixture.replayService.create(
                    ReplayRequest(scope = ReplayScope.Derived, wipe = true),
                    fixture.clock.instant(),
                )
            }
        }

    private inner class Fixture(
        poolSize: Int = 1,
    ) {
        val config: DatabaseConfig = PostgresTestDatabase.config().copy(maxPoolSize = poolSize)
        val database: Database = openDatabase(config)
        val clock = Clock.systemUTC()
        private val derivedRebuildExecutor = realDerivedRebuildExecutor(database)
        private val ingestionService = ingestionService(database, derivedRebuildExecutor)
        val replayService =
            ReplayService(
                database = database,
                ingestionRepository = IngestionRepository(),
                mappingService = IngestionMappingService(),
                metricWriteService = metricWriteService(),
                derivedRebuildExecutor = derivedRebuildExecutor,
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
                    records =
                        listOf(
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

        suspend fun ingestScalar(
            providerRecordId: String,
            measuredAt: String,
        ) {
            ingestionService.ingestBatch(
                IngestionBatchRequest(
                    provider = "withings",
                    providerInstanceId = "scale-1",
                    ingestedAt = "2026-04-19T10:02:00Z",
                    sourcePayload = buildJsonObject {},
                    records =
                        listOf(
                            ScalarSample(
                                providerRecordId = providerRecordId,
                                measuredAt = measuredAt,
                                metricType = "heart_rate",
                                value = 65.0,
                                context = "resting",
                            ),
                        ),
                ),
                Instant.parse("2026-04-19T10:02:00Z"),
            )
        }

        suspend fun runReplay(request: ReplayRequest): ReplayJobStatusResponse {
            val start = replayService.create(request, clock.instant())
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

        fun waitingLocks(
            table: String,
            mode: String,
        ): Int = config.queryInt("SELECT COUNT(*) FROM pg_locks WHERE relation = '$table'::regclass AND mode = '$mode' AND NOT granted")
    }
}
