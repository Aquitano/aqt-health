package me.aquitano.health.infrastructure.database

import me.aquitano.health.application.ApiClientBootstrapService
import me.aquitano.health.infrastructure.config.AuthConfig
import me.aquitano.health.infrastructure.repositories.SupportRepository
import me.aquitano.health.infrastructure.security.ApiKeyHasher
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.PostgresTestDatabase
import me.aquitano.health.test.queryString
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class DatabaseFactoryTest : PostgresIntegrationTest() {
    @Test
    fun migrationsCreateExpectedTables() {
        val database = openDatabase(PostgresTestDatabase.emptyConfig())

        val tableNames =
            transaction(database) {
                val names = mutableSetOf<String>()
                exec("SELECT tablename FROM pg_tables WHERE schemaname = current_schema()") { resultSet ->
                    while (resultSet.next()) {
                        names.add(resultSet.getString("tablename"))
                    }
                }
                names
            }

        assertContains(tableNames, "sources")
        assertContains(tableNames, "source_instances")
        assertContains(tableNames, "api_clients")
        assertContains(tableNames, "ingestion_batches")
        assertContains(tableNames, "ingestion_records")
        assertContains(tableNames, "step_samples")
        assertContains(tableNames, "sleep_sessions")
        assertContains(tableNames, "sleep_stages")
        assertContains(tableNames, "scalar_samples")
        assertContains(tableNames, "metric_catalog")
        assertContains(tableNames, "provider_ranks")
        assertContains(tableNames, "provider_oauth_accounts")
        assertContains(tableNames, "provider_oauth_states")
        assertContains(tableNames, "provider_sync_runs")

        val viewNames =
            transaction(database) {
                val names = mutableSetOf<String>()
                exec("SELECT viewname FROM pg_views WHERE schemaname = current_schema()") { resultSet ->
                    while (resultSet.next()) {
                        names.add(resultSet.getString("viewname"))
                    }
                }
                names
            }

        assertContains(viewNames, "canonical_scalar_samples")
        assertContains(viewNames, "canonical_activity_summaries")
        assertContains(viewNames, "canonical_sleep_summaries")
        assertContains(viewNames, "canonical_sleep_sessions")
    }

    @Test
    fun bootstrapStoresOnlyHashedApiKey() {
        val config = PostgresTestDatabase.config()
        val hasher = ApiKeyHasher()
        ApiClientBootstrapService(
            authConfig =
                AuthConfig(
                    bootstrapClientName = "test-client",
                    bootstrapApiKey = "plain-test-key",
                ),
            supportRepository = SupportRepository(openDatabase(config)),
            apiKeyHasher = hasher,
            clock = Clock.fixed(Instant.parse("2026-04-19T10:00:00Z"), ZoneOffset.UTC),
        ).bootstrap()

        assertEquals(hasher.hash("plain-test-key"), config.queryString("SELECT api_key_hash FROM api_clients WHERE name = 'test-client'"))
    }
}
