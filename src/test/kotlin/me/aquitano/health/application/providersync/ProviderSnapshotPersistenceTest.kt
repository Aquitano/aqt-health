package me.aquitano.health.application.providersync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import me.aquitano.health.api.dto.IngestionBatchRequest
import me.aquitano.health.api.dto.ScalarSample
import me.aquitano.health.application.IngestionMappingService
import me.aquitano.health.application.IngestionService
import me.aquitano.health.domain.IngestionSnapshot
import me.aquitano.health.domain.ScalarMetricTypes
import me.aquitano.health.infrastructure.repositories.IngestionRepository
import me.aquitano.health.infrastructure.repositories.PendingDerivedRebuildRepository
import me.aquitano.health.infrastructure.repositories.SupportRepository
import me.aquitano.health.test.NoOpDerivedRebuildExecutor
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.PostgresTestDatabase
import me.aquitano.health.test.metricWriteService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProviderSnapshotPersistenceTest : PostgresIntegrationTest() {
    @Test
    fun movedRecordInvalidatesItsOldWindowEvenWhenItReturnsWithTheOriginalContent() =
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
            val original = sample("2026-04-01T08:00:00Z")
            val first = service.ingestBatch(request("first", original), now, IngestionSnapshot("day-a", "hash-a"))
            assertEquals(first.batchId, service.reusableSyncBatchId("withings", "account", "day-a", "hash-a", now))

            service.ingestBatch(
                request("moved", original.copy(measuredAt = "2026-04-02T08:00:00Z")),
                now,
                IngestionSnapshot("day-b", "hash-b"),
            )
            // No empty day-a response needs to be observed before this return to the old window.
            assertNull(service.reusableSyncBatchId("withings", "account", "day-a", "hash-a", now))
            val returned = service.ingestBatch(request("returned", original), now, IngestionSnapshot("day-a", "hash-a"))
            assertEquals(returned.batchId, service.reusableSyncBatchId("withings", "account", "day-a", "hash-a", now))

            // An overlapping window can copy the same record without making either cache stale.
            service.ingestBatch(request("overlap", original), now, IngestionSnapshot("overlap", "hash-overlap"))
            assertEquals(returned.batchId, service.reusableSyncBatchId("withings", "account", "day-a", "hash-a", now))
            assertNotNull(service.reusableSyncBatchId("withings", "account", "overlap", "hash-overlap", now))
            Unit
        }

    @Test
    fun snapshotValidityUsesScalarIdentityDefaultsAndIgnoresOtherSourcesAndFailedVersions() =
        runBlocking {
            val config = PostgresTestDatabase.config()
            val database = openDatabase(config)
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
            val original = sample("2026-04-01T08:00:00Z")
            service.ingestBatch(request("first", original), now, IngestionSnapshot("window", "hash"))
            service.ingestBatch(request("explicit-default", original.copy(context = "unknown", unit = "bpm")), now)
            service.ingestBatch(request("other-context", original.copy(context = "sleep", value = 65.0)), now)
            service.ingestBatch(request("other-metric", original.copy(metricType = ScalarMetricTypes.RESPIRATORY_RATE, value = 20.0)), now)
            service.ingestBatch(request("other-source", original.copy(value = 66.0)).copy(providerInstanceId = "another-account"), now)
            val failed = service.ingestBatch(request("failed", original.copy(value = 67.0)), now)
            PostgresTestDatabase.connection(config).use { connection ->
                connection.createStatement().use { it.executeUpdate("UPDATE ingestion_batches SET status = 'failed' WHERE id = ${failed.batchId}") }
            }
            assertNotNull(service.reusableSyncBatchId("withings", "account", "window", "hash", now))

            service.ingestBatch(request("changed", original.copy(context = "unknown", value = 68.0)), now)
            assertNull(service.reusableSyncBatchId("withings", "account", "window", "hash", now))
        }

    private fun sample(measuredAt: String) =
        ScalarSample(
            providerRecordId = "stable-id",
            measuredAt = measuredAt,
            metricType = ScalarMetricTypes.HEART_RATE,
            value = 60.0,
        )

    private fun request(
        id: String,
        record: ScalarSample,
    ) = IngestionBatchRequest(
        provider = "withings",
        providerInstanceId = "account",
        batchExternalId = id,
        ingestedAt = now.toString(),
        sourcePayload = buildJsonObject {},
        records = listOf(record),
    )

    private val now = Instant.parse("2026-04-03T10:00:00Z")
}
