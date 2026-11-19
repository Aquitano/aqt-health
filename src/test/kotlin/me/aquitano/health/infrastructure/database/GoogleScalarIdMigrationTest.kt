package me.aquitano.health.infrastructure.database

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.aquitano.health.api.dto.ReplayJobStatusResponse
import me.aquitano.health.api.dto.ReplayRequest
import me.aquitano.health.application.IngestionMappingService
import me.aquitano.health.application.ReplayService
import me.aquitano.health.domain.ReplayJobStatus
import me.aquitano.health.infrastructure.repositories.IngestionRepository
import me.aquitano.health.infrastructure.repositories.PendingDerivedRebuildRepository
import me.aquitano.health.infrastructure.repositories.ProjectionWipeRepository
import me.aquitano.health.infrastructure.repositories.ReplayJobRepository
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.PostgresTestDatabase
import me.aquitano.health.test.execute
import me.aquitano.health.test.metricWriteService
import me.aquitano.health.test.queryInt
import me.aquitano.health.test.queryString
import me.aquitano.health.test.realDerivedRebuildExecutor
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleScalarIdMigrationTest : PostgresIntegrationTest() {
    @Test
    fun googleScalarIdsLoseTheirContentHashUnlessTwoRecordsOfOneBatchWouldShareOneIdAndReplayKeepsOneRowPerReading() =
        runBlocking {
            val config = PostgresTestDatabase.config()
            val database = openDatabase(config)
            val (hashA, hashB, hashC, hashD) = listOf("a", "b", "c", "d").map { it.repeat(64) }
            val resent = "heart-rate:2026-04-01T08:00:00Z:none"
            val sameSecond = "heart-rate:2026-04-01T08:00:05Z:none"
            val withings = "withings:2026-04-01T08:00:00Z:$hashA"

            fun record(
                id: String,
                measuredAt: String,
                value: Int,
            ) = """{"type":"scalar","providerRecordId":"$id","measuredAt":"$measuredAt","metricType":"heart_rate","value":$value,"context":"unknown"}"""
            config.execute(
                """
                INSERT INTO sources (id, code, created_at) VALUES (1, 'google_health', now()), (2, 'withings', now());
                INSERT INTO source_instances (id, source_id, provider_instance_id, created_at, updated_at)
                VALUES (1, 1, 'google-health-me', now(), now()), (2, 2, 'withings-1', now(), now());
                INSERT INTO ingestion_batches (id, source_instance_id, source_payload_json, status, ingested_at, received_at, created_at, updated_at)
                VALUES (1, 1, '{}', 'processed', now(), now(), now(), now()),
                       (2, 1, '{}', 'processed', now(), now(), now(), now()),
                       (3, 2, '{}', 'processed', now(), now(), now(), now());
                INSERT INTO ingestion_records (id, batch_id, record_type, provider_record_id, normalized_record_json, record_start_at, created_at)
                VALUES (1, 1, 'scalar', '$resent:$hashA', '${record("$resent:$hashA", "2026-04-01T08:00:00Z", 60)}', '2026-04-01T08:00:00Z', now()),
                       (2, 1, 'scalar', '$sameSecond:$hashB', '${record("$sameSecond:$hashB", "2026-04-01T08:00:05Z", 61)}', '2026-04-01T08:00:05Z', now()),
                       (3, 1, 'scalar', '$sameSecond:$hashC', '${record("$sameSecond:$hashC", "2026-04-01T08:00:05Z", 62)}', '2026-04-01T08:00:05Z', now()),
                       (4, 2, 'scalar', '$sameSecond:$hashB', '${record("$sameSecond:$hashB", "2026-04-01T08:00:05Z", 61)}', '2026-04-01T08:00:05Z', now()),
                       (5, 2, 'scalar', '$resent:$hashD', '${record("$resent:$hashD", "2026-04-01T08:00:00Z", 60)}', '2026-04-01T08:00:00Z', now()),
                       (6, 3, 'scalar', '$withings', '${record(withings, "2026-04-01T08:00:00Z", 59)}', '2026-04-01T08:00:00Z', now());
                INSERT INTO scalar_samples (id, source_instance_id, ingestion_record_id, provider_record_id, measured_at, metric_type, value, context, created_at)
                VALUES (1, 1, 1, '$resent:$hashA', '2026-04-01T08:00:00Z', 'heart_rate', 60, 'unknown', now()),
                       (2, 1, 4, '$sameSecond:$hashB', '2026-04-01T08:00:05Z', 'heart_rate', 61, 'unknown', now()),
                       (3, 1, 3, '$sameSecond:$hashC', '2026-04-01T08:00:05Z', 'heart_rate', 62, 'unknown', now()),
                       (4, 2, 6, '$withings', '2026-04-01T08:00:00Z', 'heart_rate', 59, 'unknown', now()),
                       (5, 1, 5, '$resent:$hashD', '2026-04-01T08:00:00Z', 'heart_rate', 60, 'unknown', now());
                """.trimIndent(),
            )
            val migration =
                checkNotNull(javaClass.getResource("/db/migration/V40__google_scalar_ids_without_content_hash.sql")).readText()

            repeat(2) { config.execute(migration) }

            fun ids(table: String) = config.queryString("SELECT string_agg(provider_record_id, ' ' ORDER BY id) FROM $table")?.split(' ')
            assertEquals(listOf(resent, "$sameSecond:$hashB", "$sameSecond:$hashC", "$sameSecond:$hashB", resent, withings), ids("ingestion_records"))
            // Only the newest row of a resent reading takes the new id; the older row's log record is superseded by it.
            assertEquals(listOf("$resent:$hashA", "$sameSecond:$hashB", "$sameSecond:$hashC", withings, resent), ids("scalar_samples"))
            assertEquals(0, config.queryInt("SELECT COUNT(*) FROM ingestion_records WHERE normalized_record_json->>'providerRecordId' IS DISTINCT FROM provider_record_id"))

            val now = Instant.parse("2026-05-01T00:00:00Z")
            val replay =
                ReplayService(
                    database,
                    IngestionRepository(),
                    IngestionMappingService(),
                    metricWriteService(),
                    realDerivedRebuildExecutor(database),
                    PendingDerivedRebuildRepository(database),
                    ReplayJobRepository(database),
                    ProjectionWipeRepository(),
                    Clock.fixed(now, ZoneOffset.UTC),
                )

            suspend fun replay(wipe: Boolean): ReplayJobStatusResponse {
                val job = replay.create(ReplayRequest(wipe = wipe), now)
                return withTimeout(30_000) {
                    var status = replay.get(job.jobId)
                    while (!status.status.terminal) {
                        delay(20)
                        status = replay.get(job.jobId)
                    }
                    status
                }.also { assertEquals(ReplayJobStatus.Completed, it.status, it.errorMessage) }
            }
            try {
                assertEquals(0, replay(wipe = false).metricsWritten)

                replay(wipe = true)

                assertEquals(
                    "$resent=60 $sameSecond:$hashB=61 $sameSecond:$hashC=62 $withings=59",
                    config.queryString("SELECT string_agg(provider_record_id || '=' || value::int, ' ' ORDER BY measured_at, value) FROM scalar_samples WHERE source_instance_id = 1") +
                        " " + config.queryString("SELECT string_agg(provider_record_id || '=' || value::int, ' ') FROM scalar_samples WHERE source_instance_id = 2"),
                )
            } finally {
                replay.stop()
            }
        }
}
