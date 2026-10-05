package me.aquitano.health.infrastructure.database

import me.aquitano.health.infrastructure.config.DatabaseConfig
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.PostgresTestDatabase
import me.aquitano.health.test.execute
import me.aquitano.health.test.queryString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PostgresTestDatabaseTest : PostgresIntegrationTest() {
    @Test
    fun droppingOneFixtureSchemaPreservesOtherFixturesAndTheirIndexes() {
        val base = PostgresTestDatabase.emptyConfig()
        val extensionSchemaSql =
            """
            SELECT n.nspname FROM pg_extension e
            JOIN pg_namespace n ON n.oid = e.extnamespace
            WHERE e.extname = 'btree_gist'
            """.trimIndent()
        val expectedExtensionSchema = base.queryString("SELECT coalesce(($extensionSchemaSql), '${PostgresTestDatabase.EXTENSIONS_SCHEMA}')")

        fun fixture(): DatabaseConfig =
            checkNotNull(
                PostgresTestDatabase.externalConfig(base.jdbcUrl, base.user, base.password, required = true),
            ).also { FlywayMigrator().migrate(it) }

        val first = fixture()
        val second = fixture()
        val schema = assertNotNull(first.queryString("SELECT current_schema()"))
        assertTrue(schema.startsWith("aqt_health_test_"))
        first.execute("DROP SCHEMA $schema CASCADE")

        listOf(second, fixture()).forEach { config ->
            assertEquals(expectedExtensionSchema, config.queryString(extensionSchemaSql))
            config.execute("REINDEX INDEX step_samples_source_instance_time_range_gist_idx")
        }
    }
}
