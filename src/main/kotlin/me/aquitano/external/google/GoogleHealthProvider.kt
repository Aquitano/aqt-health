package me.aquitano.external.google

import io.ktor.http.*
import me.aquitano.external.oauthConfigurationIssues
import me.aquitano.external.persistOAuthConnection
import me.aquitano.external.requireProviderConfigured
import me.aquitano.health.application.providersync.ProviderSyncAdapter
import me.aquitano.health.application.providersync.ProviderSyncPipeline
import me.aquitano.health.domain.*
import me.aquitano.health.infrastructure.config.ProviderOAuthConfig
import me.aquitano.health.infrastructure.repositories.ProviderOAuthRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import me.aquitano.health.infrastructure.logging.*
import java.time.Instant

private val logger = KotlinLogging.logger {}

class GoogleHealthProvider(
    private val config: ProviderOAuthConfig,
    private val repository: ProviderOAuthRepository,
    private val client: GoogleHealthClient,
    normalizer: GoogleHealthNormalizer,
    private val syncPipeline: ProviderSyncPipeline,
    private val syncAdapter: ProviderSyncAdapter = GoogleHealthSyncAdapter(client, normalizer),
) : HealthProvider {

    override val providerCode: String = GOOGLE_HEALTH_PROVIDER_CODE
    override val descriptor: HealthProviderDescriptor =
        HealthProviderDescriptor(
            providerCode = "google-health",
            displayName = "Google Health",
            authType = ProviderAuthType.OAUTH,
            requiresAuthentication = true,
            supportedDataTypes = GOOGLE_HEALTH_DEFAULT_DATA_TYPES,
            defaultDataTypes = GOOGLE_HEALTH_DEFAULT_DATA_TYPES,
            maxSyncRangeDays = 31,
            supportsPageSize = true,
            workflowEndpoints = ProviderWorkflowEndpoints(
                oauthStart = "/api/v2/providers/google-health/oauth/start",
                oauthCallback = "/api/v2/providers/google-health/oauth/callback",
                accounts = "/api/v2/providers/google-health/accounts",
                disconnect = "/api/v2/providers/google-health/accounts/{providerInstanceId}/disconnect",
                reconnect = "/api/v2/providers/google-health/accounts/{providerInstanceId}/reconnect",
                sync = "/api/v2/providers/google-health/sync",
            ),
            aliases = listOf(GOOGLE_HEALTH_PROVIDER_CODE),
        )
    override val defaultProviderInstanceId: String = "google-health-me"

    override fun isConfigured(): Boolean = configurationIssues().isEmpty()

    override fun getAuthUrl(state: String): String {
        requireConfigured()
        return URLBuilder(config.oauthAuthUrl).apply {
            parameters.append("client_id", config.clientId)
            parameters.append("redirect_uri", config.redirectUri)
            parameters.append("response_type", "code")
            parameters.append("scope", GOOGLE_HEALTH_SCOPES.joinToString(" "))
            parameters.append("state", state)
            parameters.append("access_type", "offline")
            parameters.append("prompt", "consent")
        }.buildString()
    }

    override suspend fun connect(
        code: String,
        now: Instant
    ): ProviderConnection {
        requireConfigured()
        val tokens = try {
            client.exchangeCode(code, now)
        } catch (exception: GoogleHealthHttpException) {
            logger.warnWithContext(
                "provider_token_exchange_failed",
                "provider" to GOOGLE_HEALTH_PROVIDER_CODE,
                "errorCode" to exception.code,
                throwable = exception,
            )
            throw UpstreamProviderException(
                code = exception.code,
                message = exception.message ?: "Google OAuth token exchange failed",
                statusCode = 502,
                cause = exception,
            )
        }
        val refreshToken = tokens.refreshToken
            ?: throw UpstreamProviderException(
                code = "google_health_missing_refresh_token",
                message = "Google OAuth response did not include a refresh token; start OAuth again with prompt=consent",
                statusCode = 502,
            )
        return persistOAuthConnection(
            repository = repository,
            config = config,
            providerCode = GOOGLE_HEALTH_PROVIDER_CODE,
            providerUserId = defaultProviderInstanceId,
            providerInstanceId = defaultProviderInstanceId,
            tokens = tokens,
            refreshToken = refreshToken,
            scopeDelimiter = " ",
            now = now,
        )
    }

    override suspend fun sync(
        request: ProviderSyncRequest,
        now: Instant,
        progress: me.aquitano.health.application.providersync.ProviderSyncProgressSink,
    ): ProviderSyncSummary = syncPipeline.sync(syncAdapter, request, now, progress)

    private fun requireConfigured() =
        requireProviderConfigured("google_health_not_configured", configurationIssues())

    private fun configurationIssues(): List<ValidationIssue> =
        config.oauthConfigurationIssues("googleHealth")
}
