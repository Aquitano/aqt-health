package me.aquitano.health.infrastructure.database

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.aquitano.health.api.dto.ReplayRequest
import me.aquitano.health.application.IngestionMappingService
import me.aquitano.health.application.ReplayService
import me.aquitano.health.domain.ReplayJobStatus
import me.aquitano.health.infrastructure.repositories.IngestionRepository
import me.aquitano.health.infrastructure.repositories.ProjectionWipeRepository
import me.aquitano.health.infrastructure.repositories.ReplayJobRepository
import me.aquitano.health.infrastructure.time.UtcClock
import me.aquitano.health.test.*
import org.flywaydb.core.Flyway
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderCorrectionMigrationTest : PostgresIntegrationTest() {
    @Test
    fun backfillPreservesGoogleIdentityAcceptanceAndRawPayloadsThroughReplay() = runBlocking {
        val config = PostgresTestDatabase.config()
        Flyway.configure().dataSource(config.jdbcUrl, config.user, config.password)
            .locations("classpath:db/migration").target("30").load().migrate()
        PostgresTestDatabase.connection(config).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("INSERT INTO sources (id, code, created_at) VALUES (1, 'google_health', now())")
                statement.execute("""
                    INSERT INTO source_instances (id, source_id, provider_instance_id, created_at, updated_at)
                    VALUES (1, 1, 'migration', now(), now())
                """.trimIndent())
                statement.execute("""
                    INSERT INTO ingestion_batches (id, source_instance_id, source_payload_json, status,
                        ingested_at, received_at, processed_at, created_at, updated_at)
                    SELECT id, 1, '{}', 'processed', now(), now(), now(), now(), now()
                    FROM generate_series(1, 3) id
                """.trimIndent())
                statement.execute("""
                    INSERT INTO ingestion_records (id, batch_id, record_type, provider_record_id,
                        normalized_record_json, record_start_at, record_end_at, created_at)
                    SELECT id, id, 'step_interval', CASE WHEN id = 2 THEN 'skipped' ELSE 'accepted' END,
                        jsonb_build_object('type', 'step_interval', 'providerRecordId', CASE WHEN id = 2 THEN 'skipped' ELSE 'accepted' END,
                            'startAt', CASE WHEN id = 2 THEN '2026-04-19T09:00:00Z' ELSE '2026-04-19T08:00:00Z' END,
                            'endAt', '2026-04-19T10:00:00Z', 'steps', CASE WHEN id = 2 THEN 100 ELSE 200 END),
                        CASE WHEN id = 2 THEN '2026-04-19T09:00:00Z'::timestamptz ELSE '2026-04-19T08:00:00Z'::timestamptz END,
                        '2026-04-19T10:00:00Z', now()
                    FROM generate_series(1, 3) id
                """.trimIndent())
                statement.execute("""
                    INSERT INTO step_samples (source_instance_id, ingestion_record_id, provider_record_id, start_at, end_at, steps, created_at)
                    VALUES (1, 1, 'accepted', '2026-04-19T08:00:00Z', '2026-04-19T10:00:00Z', 200, now())
                """.trimIndent())
            }
        }
        fun payloads() = PostgresTestDatabase.connection(config).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT normalized_record_json::text FROM ingestion_records ORDER BY id").use { result ->
                    buildList { while (result.next()) add(result.getString(1)) }
                }
            }
        }
        val originalPayloads = payloads()
        val database = openDatabase(config)
        val decisions = PostgresTestDatabase.connection(config).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT google_step_projection_accepted FROM ingestion_records ORDER BY id").use { result ->
                    buildList { while (result.next()) add(result.getBoolean(1)) }
                }
            }
        }
        assertEquals(listOf(true, false, true), decisions)
        val now = Instant.parse("2026-05-01T00:00:00Z")
        val replay = ReplayService(
            database, IngestionRepository(), IngestionMappingService(), metricWriteService(),
            realDerivedRebuildExecutor(database), derivedRebuildRegistry(), ReplayJobRepository(database),
            ProjectionWipeRepository(), UtcClock.fixed(now),
        )
        try {
            val job = replay.create(ReplayRequest(scope = "all", wipe = true), now)
            val result = withTimeout(30_000) {
                var status = replay.get(job.jobId)
                while (!status.status.terminal) {
                    delay(20)
                    status = replay.get(job.jobId)
                }
                status
            }
            assertEquals(ReplayJobStatus.Completed, result.status, result.errorMessage)
            PostgresTestDatabase.connection(config).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT provider_record_id FROM step_samples").use { rows ->
                        assertEquals(listOf("accepted"), buildList { while (rows.next()) add(rows.getString(1)) })
                    }
                }
            }
            assertEquals(originalPayloads, payloads())
        } finally {
            replay.stop()
        }
    }
}
