package me.aquitano.health.infrastructure.repositories

import kotlinx.coroutines.runBlocking
import me.aquitano.health.infrastructure.database.DatabaseFactory
import me.aquitano.health.infrastructure.config.DatabaseConfig
import me.aquitano.health.infrastructure.security.ApiKeyHasher
import me.aquitano.health.test.PostgresTestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SupportRepositoryTest {
    @Test
    fun apiClientLastUsedAtIsThrottled() = runBlocking {
        val database = DatabaseFactory().initialize(tempDatabaseConfig())
        val repository = SupportRepository(database)
        val hasher = ApiKeyHasher()
        val apiKeyHash = hasher.hash("test-key")
        repository.upsertBootstrapApiClient(
            name = "test-client",
            apiKeyHash = apiKeyHash,
            now = Instant.parse("2026-05-15T09:00:00Z"),
        )

        val first = repository.findEnabledApiClientByHash(
            apiKeyHash = apiKeyHash,
            now = Instant.parse("2026-05-15T10:00:00Z"),
        )
        assertNotNull(first)
        assertEquals("2026-05-15T10:00:00Z", lastUsedAt(database))

        repository.findEnabledApiClientByHash(
            apiKeyHash = apiKeyHash,
            now = Instant.parse("2026-05-15T10:00:30Z"),
        )
        assertEquals("2026-05-15T10:00:00Z", lastUsedAt(database))

        repository.findEnabledApiClientByHash(
            apiKeyHash = apiKeyHash,
            now = Instant.parse("2026-05-15T10:01:01Z"),
        )
        assertEquals("2026-05-15T10:01:01Z", lastUsedAt(database))
    }

    @Test
    fun bootstrapClientHashIsRewrittenWhenTheConfiguredKeyRotates() = runBlocking {
        val database = DatabaseFactory().initialize(tempDatabaseConfig())
        val repository = SupportRepository(database)
        val hasher = ApiKeyHasher()
        val now = Instant.parse("2026-05-15T09:00:00Z")

        assertEquals(
            BootstrapApiClientOutcome.CREATED,
            repository.upsertBootstrapApiClient("test-client", hasher.hash("old-key"), now),
        )
        assertEquals(
            BootstrapApiClientOutcome.UNCHANGED,
            repository.upsertBootstrapApiClient("test-client", hasher.hash("old-key"), now),
        )
        assertEquals(
            BootstrapApiClientOutcome.ROTATED,
            repository.upsertBootstrapApiClient("test-client", hasher.hash("new-key"), now),
        )

        assertNull(repository.findEnabledApiClientByHash(hasher.hash("old-key"), now))
        assertEquals(
            "test-client",
            repository.findEnabledApiClientByHash(hasher.hash("new-key"), now)?.name,
        )
    }

    private fun tempDatabaseConfig(): DatabaseConfig = PostgresTestDatabase.config()

    private fun lastUsedAt(database: org.jetbrains.exposed.v1.jdbc.Database): String? =
        transaction(database) {
            var value: String? = null
            exec("SELECT last_used_at FROM api_clients WHERE name = 'test-client'") { resultSet ->
                if (resultSet.next()) {
                    value = resultSet.getObject(1, OffsetDateTime::class.java)
                        ?.toInstant()
                        ?.toString()
                }
            }
            value
        }
}
