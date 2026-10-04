package me.aquitano.health.infrastructure.database

import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.PostgresTestDatabase
import org.flywaydb.core.Flyway
import kotlin.test.Test
import kotlin.test.assertEquals

class DerivedRecoveryMigrationTest : PostgresIntegrationTest() {
    @Test
    fun rawStepsWithoutCanonicalRowsQueueEveryTouchedUtcDate() {
        val config = PostgresTestDatabase.emptyConfig()
        Flyway
            .configure()
            .dataSource(config.jdbcUrl, config.user, config.password)
            .locations("classpath:db/migration")
            .target("29")
            .load()
            .migrate()
        PostgresTestDatabase.connection(config).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("INSERT INTO sources (id, code, created_at) VALUES (1, 'health_connect', now())")
                statement.execute(
                    """
                    INSERT INTO source_instances (id, source_id, provider_instance_id, created_at, updated_at)
                    VALUES (1, 1, 'recovery', now(), now())
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO step_samples (source_instance_id, start_at, end_at, steps, created_at)
                    VALUES (1, '2026-04-19T23:00:00Z', '2026-04-21T00:00:00Z', 300, now())
                    """.trimIndent(),
                )
            }
        }
        FlywayMigrator().migrate(config)
        val dates =
            PostgresTestDatabase.connection(config).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT affected_date::text FROM pending_derived_rebuilds ORDER BY affected_date").use { rows ->
                        buildList { while (rows.next()) add(rows.getString(1)) }
                    }
                }
            }
        assertEquals(listOf("2026-04-19", "2026-04-20"), dates)
    }
}
