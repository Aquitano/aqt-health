package me.aquitano.external.withings

import kotlinx.coroutines.runBlocking
import me.aquitano.health.application.HealthProviderRegistry
import me.aquitano.health.application.ProviderWorkflowService
import me.aquitano.health.domain.ConflictException
import me.aquitano.health.domain.ProviderSyncRequest
import me.aquitano.health.domain.UpstreamProviderException
import me.aquitano.health.infrastructure.config.DatabaseConfig
import me.aquitano.health.infrastructure.config.WithingsConfig
import me.aquitano.health.infrastructure.database.DatabaseFactory
import me.aquitano.health.infrastructure.repositories.ProviderOAuthRepository
import me.aquitano.health.infrastructure.security.TokenCipher
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WithingsProviderTest {
    @Test
    fun oauthStartUrlContainsWithingsParameters() = runBlocking {
        val fixture = Fixture()
        val start = fixture.providerWorkflowService.startOAuth("withings", fixture.now)

        assertTrue(start.authorizationUrl.startsWith("https://account.withings.com/oauth2_user/authorize2?"))
        assertTrue(start.authorizationUrl.contains("response_type=code"))
        assertTrue(start.authorizationUrl.contains("client_id=client-id"))
        assertTrue(start.authorizationUrl.contains("scope=user.info%2Cuser.metrics%2Cuser.activity"))
        assertTrue(start.authorizationUrl.contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fv1%2Fproviders%2Fwithings%2Foauth%2Fcallback"))
        assertTrue(start.authorizationUrl.contains("state="))
    }

    @Test
    fun oauthCallbackStoresEncryptedTokens() = runBlocking {
        val fixture = Fixture()
        val start = fixture.providerWorkflowService.startOAuth("withings", fixture.now)
        val state = Regex("state=([^&]+)").find(start.authorizationUrl)!!.groupValues[1]

        val response = fixture.providerWorkflowService.completeOAuth(
            providerCode = "withings",
            code = "auth-code",
            state = state,
            error = null,
            now = fixture.now.plusSeconds(1),
        )

        assertEquals("withings-363", response.providerInstanceId)
        assertEquals("363", singleString(fixture.dbPath, "SELECT provider_user_id FROM provider_oauth_accounts"))
        assertEquals("withings-363", singleString(fixture.dbPath, "SELECT provider_instance_id FROM provider_oauth_accounts"))
        val accessCiphertext = singleString(fixture.dbPath, "SELECT access_token_ciphertext FROM provider_oauth_accounts")
        val refreshCiphertext = singleString(fixture.dbPath, "SELECT refresh_token_ciphertext FROM provider_oauth_accounts")
        assertFalse(accessCiphertext.contains("access-from-code"))
        assertFalse(refreshCiphertext.contains("refresh-from-code"))
        val cipher = TokenCipher(fixture.config.tokenEncryptionKey)
        assertEquals("access-from-code", cipher.decrypt(accessCiphertext))
        assertEquals("refresh-from-code", cipher.decrypt(refreshCiphertext))
    }

    @Test
    fun oauthCallbackMapsTokenExchangeFailureToUpstreamProviderError() = runBlocking {
        val fixture = Fixture()
        val start = fixture.providerWorkflowService.startOAuth("withings", fixture.now)
        val state = Regex("state=([^&]+)").find(start.authorizationUrl)!!.groupValues[1]
        fixture.client.nextExchangeFailure = WithingsHttpException(
            "withings_token_request_failed",
            "Withings OAuth token request failed with 400",
        )

        val error = assertFailsWith<UpstreamProviderException> {
            fixture.providerWorkflowService.completeOAuth(
                providerCode = "withings",
                code = "auth-code",
                state = state,
                error = null,
                now = fixture.now.plusSeconds(1),
            )
        }

        assertEquals("withings_token_request_failed", error.code)
        assertEquals(502, error.statusCode)
    }

    @Test
    fun syncReturnsNotImplementedConflict() = runBlocking {
        val fixture = Fixture()

        val error = assertFailsWith<ConflictException> {
            fixture.provider.sync(
                ProviderSyncRequest(
                    from = Instant.parse("2026-04-01T00:00:00Z"),
                    to = Instant.parse("2026-04-02T00:00:00Z"),
                ),
                fixture.now,
            )
        }

        assertEquals("withings_sync_not_implemented", error.code)
    }

    private class Fixture(
        val dbPath: Path = Files.createTempFile("aqt-health-withings-provider-test", ".db"),
        val now: Instant = Instant.parse("2026-04-20T10:00:00Z"),
    ) {
        val config = WithingsConfig(
            clientId = "client-id",
            clientSecret = "client-secret",
            redirectUri = "http://localhost:8080/api/v1/providers/withings/oauth/callback",
            tokenEncryptionKey = "test-token-encryption-key-with-32-bytes",
            oauthTokenUrl = "https://wbsapi.withings.net/v2/oauth2",
            oauthAuthUrl = "https://account.withings.com/oauth2_user/authorize2",
        )
        private val database = DatabaseFactory().initialize(
            DatabaseConfig(
                jdbcUrl = "jdbc:sqlite:$dbPath",
                driver = "org.sqlite.JDBC",
            )
        )
        private val providerRepository = ProviderOAuthRepository(database)
        val client = FakeWithingsOAuthClient()
        val provider = WithingsProvider(
            config = config,
            repository = providerRepository,
            client = client,
        )
        val providerWorkflowService = ProviderWorkflowService(
            providerRegistry = HealthProviderRegistry(listOf(provider)),
            providerOAuthRepository = providerRepository,
        )
    }

    private class FakeWithingsOAuthClient : WithingsOAuthClient {
        var nextExchangeFailure: WithingsHttpException? = null

        override suspend fun exchangeCode(code: String, now: Instant): WithingsTokenSet {
            nextExchangeFailure?.let { throw it }
            return WithingsTokenSet(
                providerUserId = "363",
                accessToken = "access-from-code",
                refreshToken = "refresh-from-code",
                tokenType = "Bearer",
                expiresAt = now.plusSeconds(10800),
                scope = "user.info,user.metrics",
            )
        }

        override suspend fun refreshToken(refreshToken: String, now: Instant): WithingsTokenSet =
            error("not used")
    }
}

private fun singleString(dbPath: Path, sql: String): String =
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                resultSet.next()
                resultSet.getString(1)
            }
        }
    }
