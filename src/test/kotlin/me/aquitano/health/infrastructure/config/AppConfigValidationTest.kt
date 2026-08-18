package me.aquitano.health.infrastructure.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class AppConfigValidationTest {
    @Test
    fun productionConfigWithRealSecretsPasses() {
        productionConfig().validateForStartup()
    }

    @Test
    fun productionRejectsABootstrapApiKeyShorterThan32Bytes() {
        val config = productionConfig(bootstrapApiKey = "0123456789abcdef")

        val issues = runCatching { config.validateForStartup() }
            .exceptionOrNull()
            .let { it as? AppConfigValidationException }
            ?.issues
            ?: fail("expected production validation to fail")

        assertEquals(1, issues.size)
        assertEquals("aqtHealth.auth.bootstrapApiKey", issues.single().path)
        assertTrue("32 bytes" in issues.single().message)
    }

    private fun productionConfig(
        bootstrapApiKey: String = "0123456789abcdef0123456789abcdef",
    ): AppConfig =
        AppConfig(
            environment = RuntimeEnvironment.PRODUCTION,
            database = DatabaseConfig(
                jdbcUrl = "jdbc:postgresql://db.aqt-health.internal:5432/aqt_health",
                driver = "org.postgresql.Driver",
                user = "aqt_health_app",
                password = "a-real-database-password",
                maxPoolSize = 10,
            ),
            auth = AuthConfig(
                bootstrapClientName = "production-admin",
                bootstrapApiKey = bootstrapApiKey,
            ),
            googleHealth = providerConfig("https://api.aqt-health.app/api/v2/providers/google-health/oauth/callback"),
            withings = providerConfig("https://api.aqt-health.app/api/v2/providers/withings/oauth/callback"),
            cors = CorsConfig(origins = listOf("https://app.aqt-health.app")),
            openObserve = OpenObserveConfig(url = "", org = "", user = "", password = ""),
        )

    private fun providerConfig(redirectUri: String): ProviderOAuthConfig =
        ProviderOAuthConfig(
            clientId = "a-real-client-id",
            clientSecret = "a-real-client-secret",
            redirectUri = redirectUri,
            tokenEncryptionKey = "fedcba9876543210fedcba9876543210",
            apiBaseUrl = "https://api.provider.test",
            oauthTokenUrl = "https://api.provider.test/oauth/token",
            oauthAuthUrl = "https://api.provider.test/oauth/authorize",
        )
}
