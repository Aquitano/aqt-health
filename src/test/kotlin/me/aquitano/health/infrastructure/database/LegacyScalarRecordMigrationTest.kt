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
        // Pinned before the associate below, which would hide an extra record sharing a
        // metric type: 5 seeded records, of which the 2-metric body measurement fans out.
        assertEquals(6, records.size, "unexpected record count: ${records.map { it.providerRecordId }}")
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
            setOf("heart_rate", "hrv_rmssd", "weight", "muscle", "body_fat", "visceral_fat"),
            samplesByMetricType.keys,
        )
        assertEquals(64.0, samplesByMetricType.getValue("heart_rate").first.value)
        assertEquals("resting", samplesByMetricType.getValue("heart_rate").first.context)
        assertEquals(42.5, samplesByMetricType.getValue("hrv_rmssd").first.value)

        // The renamed heart rate id has to land on the record row and inside its normalized JSON.
        assertEquals(
            "withings:measure:123:heart_rate",
            samplesByMetricType.getValue("heart_rate").second.providerRecordId,
        )
        assertEquals(
            "withings:measure:123:heart_rate",
            samplesByMetricType.getValue("heart_rate").first.providerRecordId,
        )

        // The multi-value body_measurement record fanned out and the group's standalone heart
        // rate was renamed, so every scalar sample now carries the per-metric provider record
        // id the normalizers emit and a re-sync deduplicates against it.
        assertEquals(
            "withings:measure:123:weight",
            samplesByMetricType.getValue("weight").second.providerRecordId,
        )
        assertEquals(
            "withings:measure:123:muscle",
            samplesByMetricType.getValue("muscle").second.providerRecordId,
        )
        // A single-metric record keeps its id, so a Google re-sync still deduplicates.
        assertEquals(
            "body-fat:2026-04-19T07:00:00Z:none:abc123",
            samplesByMetricType.getValue("body_fat").second.providerRecordId,
        )
        // A single-metric Withings group is the one case that is renamed without fanning out.
        assertEquals(
            "withings:measure:456:visceral_fat",
            samplesByMetricType.getValue("visceral_fat").second.providerRecordId,
        )
        assertEquals(
            mapOf(
                "weight" to "withings:measure:123:weight",
                "muscle" to "withings:measure:123:muscle",
                "body_fat" to "body-fat:2026-04-19T07:00:00Z:none:abc123",
                "visceral_fat" to "withings:measure:456:visceral_fat",
                "heart_rate" to "withings:measure:123:heart_rate",
            ),
            sampleProviderRecordIds(config),
        )
        val recordIdByMetricType = samplesByMetricType.mapValues { (_, value) -> value.second.id }
        assertEquals(
            listOf("heart_rate", "weight", "muscle", "body_fat", "visceral_fat")
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
            VALUES (1, 1, 'heart_rate', 'withings:measure:123:heart-pulse',
                    '{"type":"heart_rate","providerRecordId":"withings:measure:123:heart-pulse","measuredAt":"2026-04-19T08:30:00Z","bpm":64,"context":"resting"}',
                    '2026-04-19T08:30:00Z', NULL, '2026-04-19T10:00:00Z'),
                   (2, 1, 'hrv', 'hrv-1',
                    '{"type":"hrv","providerRecordId":"hrv-1","measuredAt":"2026-04-19T02:30:00Z","metricType":"rmssd","value":42.5,"unit":"ms","context":"sleep"}',
                    '2026-04-19T02:30:00Z', NULL, '2026-04-19T10:00:00Z'),
                   (3, 1, 'body_measurement', 'withings:measure:123:body',
                    '{"type":"body_measurement","providerRecordId":"withings:measure:123:body","measuredAt":"2026-04-19T07:00:00Z","weightKg":82.4,"muscleKg":34.7}',
                    '2026-04-19T07:00:00Z', NULL, '2026-04-19T10:00:00Z'),
                   -- A Google weight/body-fat point is a single-metric body_measurement whose id
                   -- the normalizer still emits verbatim, so the conversion must not rename it.
                   (4, 1, 'body_measurement', 'body-fat:2026-04-19T07:00:00Z:none:abc123',
                    '{"type":"body_measurement","providerRecordId":"body-fat:2026-04-19T07:00:00Z:none:abc123","measuredAt":"2026-04-19T07:00:00Z","bodyFatPercent":18.2}',
                    '2026-04-19T07:00:00Z', NULL, '2026-04-19T10:00:00Z'),
                   -- A Withings group carrying one metric still has to be renamed, because its
                   -- normalizer moved off the :body id even when the group holds a single measure.
                   (5, 1, 'body_measurement', 'withings:measure:456:body',
                    '{"type":"body_measurement","providerRecordId":"withings:measure:456:body","measuredAt":"2026-04-20T07:00:00Z","visceralFatRating":9.0}',
                    '2026-04-20T07:00:00Z', NULL, '2026-04-20T10:00:00Z');

            -- The seed writes explicit ids, so advance the identity sequence the way a real
            -- database's would already be before V21 inserts its expanded records.
            SELECT setval(pg_get_serial_sequence('ingestion_records', 'id'),
                          (SELECT max(id) FROM ingestion_records));

            INSERT INTO scalar_samples (source_instance_id, ingestion_record_id, provider_record_id,
                                        measured_at, metric_type, value, unit, context, segment, created_at)
            VALUES (1, 1, 'withings:measure:123:heart-pulse', '2026-04-19T08:30:00Z', 'heart_rate', 64, 'bpm', 'resting', NULL, '2026-04-19T10:00:00Z'),
                   (1, 3, 'withings:measure:123:body', '2026-04-19T07:00:00Z', 'weight', 82.4, 'kg', NULL, NULL, '2026-04-19T10:00:00Z'),
                   (1, 3, 'withings:measure:123:body', '2026-04-19T07:00:00Z', 'muscle', 34.7, 'kg', NULL, NULL, '2026-04-19T10:00:00Z'),
                   (1, 4, 'body-fat:2026-04-19T07:00:00Z:none:abc123', '2026-04-19T07:00:00Z', 'body_fat', 18.2, 'percent', NULL, NULL, '2026-04-19T10:00:00Z'),
                   (1, 5, 'withings:measure:456:body', '2026-04-20T07:00:00Z', 'visceral_fat', 9.0, 'rating', NULL, NULL, '2026-04-20T10:00:00Z');
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
