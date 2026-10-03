package me.aquitano.health.infrastructure.database

import me.aquitano.health.infrastructure.config.DatabaseConfig
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.PostgresTestDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostgresTestDatabaseTest : PostgresIntegrationTest() {
    @Test
    fun droppingOneFixtureSchemaPreservesOtherFixturesAndTheirIndexes() {
        val base = PostgresTestDatabase.config()
        val expectedExtensionSchema = PostgresTestDatabase.connection(base).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("""
                    SELECT n.nspname FROM pg_extension e
                    JOIN pg_namespace n ON n.oid = e.extnamespace
                    WHERE e.extname = 'btree_gist'
                """.trimIndent()).use { rows ->
                    if (rows.next()) rows.getString(1) else PostgresTestDatabase.EXTENSIONS_SCHEMA
                }
            }
        }
        fun fixture(): DatabaseConfig = checkNotNull(
            PostgresTestDatabase.externalConfig(base.jdbcUrl, base.user, base.password, required = true)
        ).also { FlywayMigrator().migrate(it) }

        val first = fixture()
        val second = fixture()
        PostgresTestDatabase.connection(first).use { connection ->
            connection.createStatement().use { statement ->
                val schema = statement.executeQuery("SELECT current_schema()").use { rows ->
                    rows.next()
                    rows.getString(1)
                }
                assertTrue(schema.startsWith("aqt_health_test_"))
                statement.execute("DROP SCHEMA $schema CASCADE")
            }
        }

        listOf(second, fixture()).forEach { config ->
            PostgresTestDatabase.connection(config).use { connection ->
                connection.createStatement().use { statement ->
                    val extensionSchema = statement.executeQuery("""
                        SELECT n.nspname FROM pg_extension e
                        JOIN pg_namespace n ON n.oid = e.extnamespace
                        WHERE e.extname = 'btree_gist'
                    """.trimIndent()).use { rows ->
                        assertTrue(rows.next())
                        rows.getString(1)
                    }
                    assertEquals(expectedExtensionSchema, extensionSchema)
                    statement.execute("REINDEX INDEX step_samples_source_instance_time_range_gist_idx")
                }
            }
        }
    }
}
