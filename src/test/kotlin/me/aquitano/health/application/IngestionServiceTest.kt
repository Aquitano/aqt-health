package me.aquitano.health.application

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import me.aquitano.health.api.dto.IngestionBatchRequest
import me.aquitano.health.api.dto.ScalarSample
import me.aquitano.health.api.dto.StepInterval
import me.aquitano.health.domain.BatchStatus
import me.aquitano.health.domain.ConflictException
import me.aquitano.health.domain.IngestionSnapshot
import me.aquitano.health.domain.RequestValidationException
import me.aquitano.health.domain.ScalarMetricTypes
import me.aquitano.health.infrastructure.config.DatabaseConfig
import me.aquitano.health.infrastructure.repositories.IngestionRepository
import me.aquitano.health.infrastructure.repositories.PendingDerivedRebuildRepository
import me.aquitano.health.infrastructure.repositories.SupportRepository
import me.aquitano.health.test.NoOpDerivedRebuildExecutor
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.PostgresTestDatabase
import me.aquitano.health.test.metricWriteService
import me.aquitano.health.test.realDerivedRebuildExecutor
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IngestionServiceTest : PostgresIntegrationTest() {
    @Test
    fun emptyProviderSnapshotPersistsWhileDirectIngestionStaysStrict() =
        runBlocking {
            val database = openDatabase(PostgresTestDatabase.config())
            val service =
                IngestionService(
                    database,
                    IngestionMappingService(),
                    SupportRepository(database),
                    IngestionRepository(),
                    metricWriteService(),
                    NoOpDerivedRebuildExecutor,
                    PendingDerivedRebuildRepository(database),
                )
            val now = Instant.parse("2026-04-19T10:00:00Z")
            val request =
                IngestionBatchRequest(
                    provider = "withings",
                    providerInstanceId = "empty-account",
                    batchExternalId = "empty",
                    ingestedAt = now.toString(),
                    sourcePayload = buildJsonObject {},
                    records = emptyList(),
                )
            assertFailsWith<RequestValidationException> { service.ingestBatch(request, now) }
            val stored = service.ingestBatch(request, now, IngestionSnapshot("window", "empty"), allowEmptyRecords = true)
            assertEquals(BatchStatus.Processed, stored.status)
            assertEquals(0, stored.ingestionRecordsStored)
            assertEquals(stored.batchId, service.reusableSyncBatchId("withings", "empty-account", "window", "empty", now))

            val record = StepInterval("duplicate", "2026-04-19T08:00:00Z", "2026-04-19T09:00:00Z", 100)
            assertFailsWith<RequestValidationException> {
                service.ingestBatch(request.copy(batchExternalId = "duplicates", records = listOf(record, record)), now)
            }
            Unit
        }

    @Test
    fun ingestionCommittedAfterCanonicalSnapshotConvergesToTheNewerTotal() =
        runBlocking {
            val config = PostgresTestDatabase.config().copy(maxPoolSize = 3)
            val database = openDatabase(config)
            val pending = PendingDerivedRebuildRepository(database)
            val service =
                IngestionService(
                    database,
                    IngestionMappingService(),
                    SupportRepository(database),
                    IngestionRepository(),
                    metricWriteService(),
                    realDerivedRebuildExecutor(database),
                    pending,
                )
            val now = Instant.parse("2026-04-19T10:00:00Z")

            fun request(hour: Int) =
                IngestionBatchRequest(
                    provider = "health_connect",
                    providerInstanceId = "concurrent",
                    batchExternalId = "steps-$hour",
                    ingestedAt = now.toString(),
                    sourcePayload = buildJsonObject {},
                    records =
                        listOf(
                            StepInterval(
                                startAt = "2026-04-19T${hour.toString().padStart(2, '0')}:00:00Z",
                                endAt = "2026-04-19T${(hour + 1).toString().padStart(2, '0')}:00:00Z",
                                steps = 100,
                            ),
                        ),
                )
            PostgresTestDatabase.connection(config).use { blocker ->
                blocker.autoCommit = false
                blocker.createStatement().use {
                    it.execute("LOCK TABLE canonical_step_day_bucket_contributions IN SHARE MODE")
                }
                val first = async { service.ingestBatch(request(8), now) }
                try {
                    withTimeout(10_000) {
                        while (singleInt(
                                config,
                                """
                                SELECT COUNT(*) FROM pg_locks
                                WHERE relation = 'canonical_step_day_bucket_contributions'::regclass
                                  AND mode = 'RowExclusiveLock' AND NOT granted
                                """.trimIndent(),
                            ) == 0
                        ) {
                            delay(10)
                        }
                    }
                    // The first canonical writer holds its date lock and has checked the raw IDs.
                    val originalRevision = pending.due(now, 10).single().revision
                    val second = async { service.ingestBatch(request(9), now) }
                    withTimeout(10_000) {
                        while (singleInt(config, "SELECT COUNT(*) FROM step_samples") != 2) delay(10)
                    }
                    assertTrue(originalRevision != pending.due(now, 10).single().revision)
                    blocker.rollback()
                    withTimeout(10_000) {
                        first.await()
                        second.await()
                    }
                } finally {
                    blocker.rollback()
                }
            }
            assertEquals(200, singleInt(config, "SELECT SUM(value)::integer FROM canonical_step_day_bucket_contributions"))
            assertEquals(0, pending.due(now, 10).size)
        }

    @Test
    fun cancellationAfterCommitLeavesDurableWorkEvenWhenBatchIsRetried() =
        runBlocking {
            val config = PostgresTestDatabase.config()
            val database = openDatabase(config)
            val pending = PendingDerivedRebuildRepository(database)
            val service =
                IngestionService(
                    database,
                    IngestionMappingService(),
                    SupportRepository(database),
                    IngestionRepository(),
                    metricWriteService(),
                    object : DerivedRebuildExecutor {
                        override suspend fun rebuild(
                            requests: List<DerivedRebuildRequest>,
                            computedAt: Instant,
                        ): Unit = throw java.util.concurrent.CancellationException("request cancelled")
                    },
                    pending,
                )
            val now = Instant.parse("2026-04-19T10:00:00Z")
            val request =
                IngestionBatchRequest(
                    provider = "health_connect",
                    providerInstanceId = "cancelled",
                    batchExternalId = "cancelled",
                    ingestedAt = now.toString(),
                    sourcePayload = buildJsonObject {},
                    records = listOf(StepInterval(startAt = "2026-04-19T08:00:00Z", endAt = "2026-04-19T09:00:00Z", steps = 100)),
                )
            assertFailsWith<java.util.concurrent.CancellationException> { service.ingestBatch(request, now) }
            assertEquals(1, pending.due(now, 10).size)
            assertTrue(service.ingestBatch(request, now).duplicateBatch)
            assertEquals(1, pending.due(now, 10).size)
            val sweeper =
                PendingDerivedRebuildSweeper(
                    pending,
                    me.aquitano.health.test
                        .realDerivedRebuildExecutor(database),
                    me.aquitano.health.infrastructure.time
                        .UtcClock(),
                )
            assertEquals(1, sweeper.sweep(now))
            assertEquals(0, pending.due(now, 10).size)
            assertEquals(100, singleInt(config, "SELECT SUM(value)::integer FROM canonical_step_day_bucket_contributions"))
        }

    @Test
    fun derivedRebuildFailureDoesNotFailRawIngestion() =
        runBlocking {
            val dbConfig = PostgresTestDatabase.config()
            val database = openDatabase(dbConfig)
            val service =
                IngestionService(
                    database = database,
                    mappingService = IngestionMappingService(),
                    supportRepository = SupportRepository(database),
                    ingestionRepository = IngestionRepository(),
                    metricWriteService = metricWriteService(),
                    derivedRebuildExecutor = FailingDerivedRebuildExecutor,
                    pendingDerivedRebuildRepository = PendingDerivedRebuildRepository(database),
                )

            val response =
                service.ingestBatch(
                    IngestionBatchRequest(
                        provider = "health_connect",
                        providerInstanceId = "pixel-8-health-connect",
                        batchExternalId = "derived-fails",
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
                            ),
                    ),
                    Instant.parse("2026-04-19T10:01:00Z"),
                )

            assertEquals(BatchStatus.Processed, response.status)
            assertEquals("processed", singleString(dbConfig, "SELECT status FROM ingestion_batches"))
            assertEquals(1, singleInt(dbConfig, "SELECT COUNT(*) FROM ingestion_records"))
            assertEquals(1, singleInt(dbConfig, "SELECT COUNT(*) FROM step_samples"))
            assertEquals(0, singleInt(dbConfig, "SELECT COUNT(*) FROM canonical_step_day_bucket_contributions"))
            assertTrue(
                singleString(dbConfig, "SELECT error_message FROM ingestion_batches")
                    .startsWith("Derived rebuild failed: test derived failure"),
            )
            // The failed rebuild must be queued for the sweeper, not just marked on the batch.
            assertEquals(1, singleInt(dbConfig, "SELECT COUNT(*) FROM pending_derived_rebuilds"))
            assertEquals(
                "2026-04-19",
                singleString(dbConfig, "SELECT affected_date::text FROM pending_derived_rebuilds"),
            )
        }

    @Test
    fun unknownExistingBatchStatusReturnsConflictInsteadOfParseFailure() =
        runBlocking {
            val dbConfig = PostgresTestDatabase.config()
            val database = openDatabase(dbConfig)
            val service =
                IngestionService(
                    database = database,
                    mappingService = IngestionMappingService(),
                    supportRepository = SupportRepository(database),
                    ingestionRepository = IngestionRepository(),
                    metricWriteService = metricWriteService(),
                    derivedRebuildExecutor = FailingDerivedRebuildExecutor,
                    pendingDerivedRebuildRepository = PendingDerivedRebuildRepository(database),
                )
            val request =
                IngestionBatchRequest(
                    provider = "health_connect",
                    providerInstanceId = "pixel-8-health-connect",
                    batchExternalId = "legacy-status",
                    ingestedAt = "2026-04-20T10:00:00Z",
                    sourcePayload = buildJsonObject {},
                    records =
                        listOf(
                            StepInterval(
                                providerRecordId = "steps-legacy",
                                startAt = "2026-04-20T08:00:00Z",
                                endAt = "2026-04-20T09:00:00Z",
                                steps = 900,
                            ),
                        ),
                )

            service.ingestBatch(request, Instant.parse("2026-04-20T10:01:00Z"))
            execute(dbConfig, "ALTER TABLE ingestion_batches DROP CONSTRAINT ingestion_batches_status_check")
            execute(dbConfig, "UPDATE ingestion_batches SET status = 'legacy' WHERE batch_external_id = 'legacy-status'")

            val error =
                assertFailsWith<ConflictException> {
                    service.ingestBatch(request, Instant.parse("2026-04-20T10:02:00Z"))
                }

            assertEquals("ingestion_batch_in_progress", error.code)
            assertTrue(error.message!!.contains("status 'legacy'"))
        }

    @Test
    fun metricWriteFailureKeepsFailedBatchAndDiscardsPartialMetrics() =
        runBlocking {
            val dbConfig = PostgresTestDatabase.config()
            val database = openDatabase(dbConfig)
            val service =
                IngestionService(
                    database = database,
                    mappingService = IngestionMappingService(),
                    supportRepository = SupportRepository(database),
                    ingestionRepository = IngestionRepository(),
                    metricWriteService = metricWriteService(),
                    derivedRebuildExecutor = FailingDerivedRebuildExecutor,
                    pendingDerivedRebuildRepository = PendingDerivedRebuildRepository(database),
                )
            // Makes the step write fail at the SQL level, which aborts the transaction unless the
            // metric writes run in their own savepoint.
            execute(dbConfig, "ALTER TABLE step_samples ADD CONSTRAINT step_samples_test_reject CHECK (steps < 100)")

            assertFailsWith<Exception> {
                service.ingestBatch(
                    IngestionBatchRequest(
                        provider = "health_connect",
                        providerInstanceId = "pixel-8-health-connect",
                        batchExternalId = "metric-write-fails",
                        ingestedAt = "2026-04-21T10:00:00Z",
                        sourcePayload = buildJsonObject {},
                        records =
                            listOf(
                                StepInterval(
                                    providerRecordId = "steps-rejected",
                                    startAt = "2026-04-21T08:00:00Z",
                                    endAt = "2026-04-21T09:00:00Z",
                                    steps = 1200,
                                ),
                            ),
                    ),
                    Instant.parse("2026-04-21T10:01:00Z"),
                )
            }

            assertEquals("failed", singleString(dbConfig, "SELECT status FROM ingestion_batches"))
            assertEquals(0, singleInt(dbConfig, "SELECT COUNT(*) FROM step_samples"))
        }

    @Test
    fun sameProviderRecordIdKeepsOneSamplePerContext() =
        runBlocking {
            val dbConfig = PostgresTestDatabase.config()
            val database = openDatabase(dbConfig)
            val service =
                IngestionService(
                    database = database,
                    mappingService = IngestionMappingService(),
                    supportRepository = SupportRepository(database),
                    ingestionRepository = IngestionRepository(),
                    metricWriteService = metricWriteService(),
                    derivedRebuildExecutor = NoOpDerivedRebuildExecutor,
                    pendingDerivedRebuildRepository = PendingDerivedRebuildRepository(database),
                )

            listOf("general", "sleep").forEachIndexed { index, context ->
                service.ingestBatch(
                    IngestionBatchRequest(
                        provider = "withings",
                        providerInstanceId = "withings-1",
                        batchExternalId = "context-$context",
                        ingestedAt = "2026-04-22T10:00:00Z",
                        sourcePayload = buildJsonObject {},
                        records =
                            listOf(
                                ScalarSample(
                                    providerRecordId = "hr-1",
                                    measuredAt = "2026-04-22T08:00:00Z",
                                    metricType = ScalarMetricTypes.HEART_RATE,
                                    value = 60.0 + index,
                                    context = context,
                                ),
                            ),
                    ),
                    Instant.parse("2026-04-22T10:0$index:00Z"),
                )
            }

            assertEquals(
                2,
                singleInt(dbConfig, "SELECT COUNT(*) FROM scalar_samples WHERE provider_record_id = 'hr-1'"),
            )
        }

    private object FailingDerivedRebuildExecutor : DerivedRebuildExecutor {
        override suspend fun rebuild(
            requests: List<DerivedRebuildRequest>,
            computedAt: Instant,
        ): Unit = throw IllegalStateException("test derived failure")
    }

    private fun singleInt(
        dbConfig: DatabaseConfig,
        sql: String,
    ): Int =
        PostgresTestDatabase.connection(dbConfig).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { resultSet ->
                    resultSet.next()
                    resultSet.getInt(1)
                }
            }
        }

    private fun singleString(
        dbConfig: DatabaseConfig,
        sql: String,
    ): String =
        PostgresTestDatabase.connection(dbConfig).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { resultSet ->
                    resultSet.next()
                    resultSet.getString(1)
                }
            }
        }

    private fun execute(
        dbConfig: DatabaseConfig,
        sql: String,
    ) {
        PostgresTestDatabase.connection(dbConfig).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(sql)
            }
        }
    }
}
