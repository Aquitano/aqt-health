package me.aquitano.health.infrastructure.database

import me.aquitano.health.api.dto.IngestionRecord
import me.aquitano.health.api.dto.ScalarSample
import me.aquitano.health.application.IngestionMappingService
import me.aquitano.health.infrastructure.config.DatabaseConfig
import me.aquitano.health.shared.AppJson
import me.aquitano.health.test.PostgresTestDatabase
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V21 converts the six legacy per-family ingestion record types into the single scalar
 * record type. Migrates a database to V20, seeds it with legacy records and the scalar
 * samples they produced, then finishes migrating and checks the log is still replayable.
 */
class LegacyScalarRecordMigrationTest {
    @Test
    fun legacyRecordsBecomeReplayableScalarRecords() {
        val config = PostgresTestDatabase.config()
        migrate(config, MigrationVersion.fromVersion("20"))
        seedLegacyRecords(config)

        FlywayMigrator().migrate(config)

        val records = storedRecords(config)
        assertTrue(
            records.all { it.recordType == "scalar" },
            "unexpected surviving record types: ${records.map { it.recordType }.toSet()}",
        )

        val mappingService = IngestionMappingService()
        val samplesByMetricType = records.associate { row ->
            val dto = AppJson.decodeFromString(IngestionRecord.serializer(), row.normalizedRecordJson)
            val scalar = assertNotNull(dto as? ScalarSample, "record ${row.id} is not a scalar sample")
            assertNotNull(
                mappingService.mapRecord(dto),
                "converted record ${row.id} (${scalar.metricType}) no longer maps",
            )
            scalar.metricType to (scalar to row)
        }

        assertEquals(
            setOf("heart_rate", "hrv_rmssd", "weight", "body_fat", "muscle"),
            samplesByMetricType.keys,
        )
        assertEquals(64.0, samplesByMetricType.getValue("heart_rate").first.value)
        assertEquals("resting", samplesByMetricType.getValue("heart_rate").first.context)
        assertEquals(42.5, samplesByMetricType.getValue("hrv_rmssd").first.value)

        // The multi-value body_measurement record fanned out, and its scalar samples now
        // carry the per-metric provider record ids the normalizers emit.
        assertEquals(
            "withings:measure:123:weight",
            samplesByMetricType.getValue("weight").second.providerRecordId,
        )
        assertEquals(
            "withings:measure:123:body_fat",
            samplesByMetricType.getValue("body_fat").second.providerRecordId,
        )
        assertEquals(
            mapOf(
                "weight" to "withings:measure:123:weight",
                "body_fat" to "withings:measure:123:body_fat",
                "muscle" to "withings:measure:123:muscle",
                "heart_rate" to "hr-1",
            ),
            sampleProviderRecordIds(config),
        )
        val recordIdByMetricType = samplesByMetricType.mapValues { (_, value) -> value.second.id }
        assertEquals(
            listOf("heart_rate", "weight", "body_fat", "muscle")
                .associateWith { recordIdByMetricType.getValue(it) },
            sampleIngestionRecordIds(config),
        )
    }

    private fun migrate(config: DatabaseConfig, target: MigrationVersion) {
        Flyway.configure()
            .dataSource(config.jdbcUrl, config.user, config.password)
            .locations("classpath:db/migration")
            .target(target)
            .load()
            .migrate()
    }

    private fun seedLegacyRecords(config: DatabaseConfig) {
        execute(
            config,
            """
            INSERT INTO sources (id, code, display_name, created_at)
            VALUES (1, 'withings', NULL, '2026-04-19T00:00:00Z');

            INSERT INTO source_instances (id, source_id, provider_instance_id, display_name, created_at, updated_at)
            VALUES (1, 1, 'scale-1', NULL, '2026-04-19T00:00:00Z', '2026-04-19T00:00:00Z');

            INSERT INTO ingestion_batches (id, source_instance_id, batch_external_id, source_payload_json,
                                           normalized_payload_json, status, ingested_at, received_at,
                                           processed_at, error_message, created_at, updated_at)
            VALUES (1, 1, 'legacy-batch', '{}', '{}', 'processed', '2026-04-19T10:00:00Z',
                    '2026-04-19T10:00:00Z', '2026-04-19T10:00:00Z', NULL,
                    '2026-04-19T10:00:00Z', '2026-04-19T10:00:00Z');

            INSERT INTO ingestion_records (id, batch_id, record_type, provider_record_id,
                                           normalized_record_json, record_start_at, record_end_at, created_at)
            VALUES (1, 1, 'heart_rate', 'hr-1',
                    '{"type":"heart_rate","providerRecordId":"hr-1","measuredAt":"2026-04-19T08:30:00Z","bpm":64,"context":"resting"}',
                    '2026-04-19T08:30:00Z', NULL, '2026-04-19T10:00:00Z'),
                   (2, 1, 'hrv', 'hrv-1',
                    '{"type":"hrv","providerRecordId":"hrv-1","measuredAt":"2026-04-19T02:30:00Z","metricType":"rmssd","value":42.5,"unit":"ms","context":"sleep"}',
                    '2026-04-19T02:30:00Z', NULL, '2026-04-19T10:00:00Z'),
                   (3, 1, 'body_measurement', 'withings:measure:123:body',
                    '{"type":"body_measurement","providerRecordId":"withings:measure:123:body","measuredAt":"2026-04-19T07:00:00Z","weightKg":82.4,"bodyFatPercent":18.2,"muscleKg":34.7}',
                    '2026-04-19T07:00:00Z', NULL, '2026-04-19T10:00:00Z');

            -- The seed writes explicit ids, so advance the identity sequence the way a real
            -- database's would already be before V21 inserts its expanded records.
            SELECT setval(pg_get_serial_sequence('ingestion_records', 'id'),
                          (SELECT max(id) FROM ingestion_records));

            INSERT INTO scalar_samples (source_instance_id, ingestion_record_id, provider_record_id,
                                        measured_at, metric_type, value, unit, context, segment, created_at)
            VALUES (1, 1, 'hr-1', '2026-04-19T08:30:00Z', 'heart_rate', 64, 'bpm', 'resting', NULL, '2026-04-19T10:00:00Z'),
                   (1, 3, 'withings:measure:123:body', '2026-04-19T07:00:00Z', 'weight', 82.4, 'kg', NULL, NULL, '2026-04-19T10:00:00Z'),
                   (1, 3, 'withings:measure:123:body', '2026-04-19T07:00:00Z', 'body_fat', 18.2, 'percent', NULL, NULL, '2026-04-19T10:00:00Z'),
                   (1, 3, 'withings:measure:123:body', '2026-04-19T07:00:00Z', 'muscle', 34.7, 'kg', NULL, NULL, '2026-04-19T10:00:00Z');
            """.trimIndent(),
        )
    }

    private data class RecordRow(
        val id: Int,
        val recordType: String,
        val providerRecordId: String?,
        val normalizedRecordJson: String,
    )

    private fun storedRecords(config: DatabaseConfig): List<RecordRow> =
        query(config, "SELECT id, record_type, provider_record_id, normalized_record_json FROM ingestion_records") {
            RecordRow(it.getInt(1), it.getString(2), it.getString(3), it.getString(4))
        }

    private fun sampleProviderRecordIds(config: DatabaseConfig): Map<String, String?> =
        query(config, "SELECT metric_type, provider_record_id FROM scalar_samples") {
            it.getString(1) to it.getString(2)
        }.toMap()

    private fun sampleIngestionRecordIds(config: DatabaseConfig): Map<String, Int> =
        query(config, "SELECT metric_type, ingestion_record_id FROM scalar_samples") {
            it.getString(1) to it.getInt(2)
        }.toMap()

    private fun <T> query(
        config: DatabaseConfig,
        sql: String,
        map: (java.sql.ResultSet) -> T,
    ): List<T> =
        PostgresTestDatabase.connection(config).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { resultSet ->
                    buildList { while (resultSet.next()) add(map(resultSet)) }
                }
            }
        }

    private fun execute(config: DatabaseConfig, sql: String) {
        PostgresTestDatabase.connection(config).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(sql)
            }
        }
    }
}
