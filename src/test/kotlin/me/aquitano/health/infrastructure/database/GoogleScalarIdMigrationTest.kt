package me.aquitano.health.infrastructure.database

import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.PostgresTestDatabase
import me.aquitano.health.test.execute
import me.aquitano.health.test.queryInt
import me.aquitano.health.test.queryString
import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleScalarIdMigrationTest : PostgresIntegrationTest() {
    @Test
    fun googleScalarIdsLoseTheirContentHashUnlessTwoRowsWouldShareOneId() {
        val config = PostgresTestDatabase.config()
        openDatabase(config)
        val hashA = "a".repeat(64)
        val hashB = "b".repeat(64)
        val hashC = "c".repeat(64)
        config.execute(
            """
            INSERT INTO sources (id, code, created_at) VALUES (1, 'google_health', now()), (2, 'withings', now());
            INSERT INTO source_instances (id, source_id, provider_instance_id, created_at, updated_at)
            VALUES (1, 1, 'google-health-me', now(), now()), (2, 2, 'withings-1', now(), now());
            INSERT INTO ingestion_batches (id, source_instance_id, source_payload_json, status, ingested_at, received_at, created_at, updated_at)
            VALUES (1, 1, '{}', 'processed', now(), now(), now(), now());
            INSERT INTO ingestion_records (id, batch_id, record_type, provider_record_id, normalized_record_json, created_at)
            VALUES (1, 1, 'scalar', 'heart-rate:2026-04-01T08:00:00Z:none:$hashA',
                    '{"type":"scalar","providerRecordId":"heart-rate:2026-04-01T08:00:00Z:none:$hashA","metricType":"heart_rate","value":60}', now()),
                   (2, 1, 'scalar', 'heart-rate:2026-04-01T08:00:05Z:none:$hashB', '{"type":"scalar","providerRecordId":"heart-rate:2026-04-01T08:00:05Z:none:$hashB"}', now()),
                   (3, 1, 'scalar', 'heart-rate:2026-04-01T08:00:05Z:none:$hashC', '{"type":"scalar","providerRecordId":"heart-rate:2026-04-01T08:00:05Z:none:$hashC"}', now());
            INSERT INTO scalar_samples (id, source_instance_id, ingestion_record_id, provider_record_id, measured_at, metric_type, value, context, created_at)
            VALUES (1, 1, 1, 'heart-rate:2026-04-01T08:00:00Z:none:$hashA', '2026-04-01T08:00:00Z', 'heart_rate', 60, 'unknown', now()),
                   (2, 1, 2, 'heart-rate:2026-04-01T08:00:05Z:none:$hashB', '2026-04-01T08:00:05Z', 'heart_rate', 61, 'unknown', now()),
                   (3, 1, 3, 'heart-rate:2026-04-01T08:00:05Z:none:$hashC', '2026-04-01T08:00:05Z', 'heart_rate', 62, 'unknown', now()),
                   (4, 2, NULL, 'withings:2026-04-01T08:00:00Z:$hashA', '2026-04-01T08:00:00Z', 'heart_rate', 59, 'unknown', now());
            """.trimIndent(),
        )
        val migration =
            checkNotNull(javaClass.getResource("/db/migration/V42__google_scalar_ids_without_content_hash.sql")).readText()

        repeat(2) { config.execute(migration) }

        assertEquals("heart-rate:2026-04-01T08:00:00Z:none", config.queryString("SELECT provider_record_id FROM scalar_samples WHERE id = 1"))
        // Two records for one instant in one batch keep their ids, and so do their rows, so replay still pairs them.
        assertEquals("heart-rate:2026-04-01T08:00:05Z:none:$hashB", config.queryString("SELECT provider_record_id FROM scalar_samples WHERE id = 2"))
        assertEquals("heart-rate:2026-04-01T08:00:05Z:none:$hashC", config.queryString("SELECT provider_record_id FROM scalar_samples WHERE id = 3"))
        assertEquals("withings:2026-04-01T08:00:00Z:$hashA", config.queryString("SELECT provider_record_id FROM scalar_samples WHERE id = 4"))
        assertEquals(
            1,
            config.queryInt(
                """SELECT COUNT(*) FROM ingestion_records
                   WHERE provider_record_id = 'heart-rate:2026-04-01T08:00:00Z:none'
                     AND normalized_record_json->>'providerRecordId' = provider_record_id""",
            ),
        )
        assertEquals(2, config.queryInt("SELECT COUNT(*) FROM ingestion_records WHERE provider_record_id LIKE 'heart-rate:2026-04-01T08:00:05Z:none:%'"))
        // Every row still carries the id of the log record it came from.
        assertEquals(
            0,
            config.queryInt("SELECT COUNT(*) FROM scalar_samples s JOIN ingestion_records r ON r.id = s.ingestion_record_id WHERE s.provider_record_id <> r.provider_record_id"),
        )
    }
}
