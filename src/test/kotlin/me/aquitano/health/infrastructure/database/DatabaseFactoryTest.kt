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
