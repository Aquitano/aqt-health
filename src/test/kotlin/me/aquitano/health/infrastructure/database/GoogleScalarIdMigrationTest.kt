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
    fun googleScalarIdsLoseTheirContentHashUnlessTwoRecordsOfOneBatchWouldShareOneId() {
        val config = PostgresTestDatabase.config()
        openDatabase(config)
        val (hashA, hashB, hashC, hashD) = listOf("a", "b", "c", "d").map { it.repeat(64) }
        val resent = "heart-rate:2026-04-01T08:00:00Z:none"
        val sameSecond = "heart-rate:2026-04-01T08:00:05Z:none"
        config.execute(
            """
            INSERT INTO sources (id, code, created_at) VALUES (1, 'google_health', now()), (2, 'withings', now());
            INSERT INTO source_instances (id, source_id, provider_instance_id, created_at, updated_at)
            VALUES (1, 1, 'google-health-me', now(), now()), (2, 2, 'withings-1', now(), now());
            INSERT INTO ingestion_batches (id, source_instance_id, source_payload_json, status, ingested_at, received_at, created_at, updated_at)
            VALUES (1, 1, '{}', 'processed', now(), now(), now(), now()),
                   (2, 1, '{}', 'processed', now(), now(), now(), now());
            INSERT INTO ingestion_records (id, batch_id, record_type, provider_record_id, normalized_record_json, created_at)
            VALUES (1, 1, 'scalar', '$resent:$hashA', '{"type":"scalar","providerRecordId":"$resent:$hashA","metricType":"heart_rate","value":60}', now()),
                   (2, 1, 'scalar', '$sameSecond:$hashB', '{"type":"scalar","providerRecordId":"$sameSecond:$hashB"}', now()),
                   (3, 1, 'scalar', '$sameSecond:$hashC', '{"type":"scalar","providerRecordId":"$sameSecond:$hashC"}', now()),
                   (4, 2, 'scalar', '$sameSecond:$hashB', '{"type":"scalar","providerRecordId":"$sameSecond:$hashB"}', now()),
                   (5, 2, 'scalar', '$resent:$hashD', '{"type":"scalar","providerRecordId":"$resent:$hashD"}', now());
            INSERT INTO scalar_samples (id, source_instance_id, ingestion_record_id, provider_record_id, measured_at, metric_type, value, context, created_at)
            VALUES (1, 1, 1, '$resent:$hashA', '2026-04-01T08:00:00Z', 'heart_rate', 60, 'unknown', now()),
                   (2, 1, 4, '$sameSecond:$hashB', '2026-04-01T08:00:05Z', 'heart_rate', 61, 'unknown', now()),
                   (3, 1, 3, '$sameSecond:$hashC', '2026-04-01T08:00:05Z', 'heart_rate', 62, 'unknown', now()),
                   (4, 2, NULL, 'withings:2026-04-01T08:00:00Z:$hashA', '2026-04-01T08:00:00Z', 'heart_rate', 59, 'unknown', now()),
                   (5, 1, 5, '$resent:$hashD', '2026-04-01T08:00:00Z', 'heart_rate', 60, 'unknown', now());
            """.trimIndent(),
        )
        val migration =
            checkNotNull(javaClass.getResource("/db/migration/V42__google_scalar_ids_without_content_hash.sql")).readText()

        repeat(2) { config.execute(migration) }

        fun ids(table: String) = config.queryString("SELECT string_agg(provider_record_id, ' ' ORDER BY id) FROM $table")?.split(' ')
        assertEquals(listOf(resent, "$sameSecond:$hashB", "$sameSecond:$hashC", "$sameSecond:$hashB", resent), ids("ingestion_records"))
        // Only the newest row of a resent reading takes the new id; the older row's log record is superseded by it.
        assertEquals(listOf("$resent:$hashA", "$sameSecond:$hashB", "$sameSecond:$hashC", "withings:2026-04-01T08:00:00Z:$hashA", resent), ids("scalar_samples"))
        assertEquals(0, config.queryInt("SELECT COUNT(*) FROM ingestion_records WHERE normalized_record_json->>'providerRecordId' IS DISTINCT FROM provider_record_id"))
    }
}
