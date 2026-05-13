package me.aquitano.external.withings

import io.ktor.http.URLBuilder
import me.aquitano.health.domain.ConflictException
import me.aquitano.health.domain.HealthProvider
import me.aquitano.health.domain.ProviderConnection
import me.aquitano.health.domain.ProviderSyncRequest
import me.aquitano.health.domain.ProviderSyncSummary
import me.aquitano.health.domain.RequestValidationException
import me.aquitano.health.domain.UpstreamProviderException
import me.aquitano.health.domain.ValidationIssue
import me.aquitano.health.infrastructure.config.WithingsConfig
import me.aquitano.health.infrastructure.repositories.ProviderOAuthRepository
import me.aquitano.health.infrastructure.security.TokenCipher
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory
import java.time.Instant

private val logger = LoggerFactory.getLogger(WithingsProvider::class.java)

class WithingsProvider(
    private val config: WithingsConfig,
    private val repository: ProviderOAuthRepository,
    private val client: WithingsOAuthClient,
) : HealthProvider {
    override val providerCode: String = WITHINGS_PROVIDER_CODE
    override val defaultProviderInstanceId: String = "withings-me"

    override fun getAuthUrl(state: String): String {
        requireConfigured()
        return URLBuilder(config.oauthAuthUrl).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", config.clientId)
            parameters.append("scope", WITHINGS_SCOPES.joinToString(","))
            parameters.append("redirect_uri", config.redirectUri)
            parameters.append("state", state)
        }.buildString()
    }

    override suspend fun connect(code: String, now: Instant): ProviderConnection {
        requireConfigured()
        val tokens = try {
            client.exchangeCode(code, now)
        } catch (exception: WithingsHttpException) {
            logger.warn(
                "provider_token_exchange_failed {} {}",
                kv("provider", WITHINGS_PROVIDER_CODE),
                kv("errorCode", exception.code),
            )
            throw UpstreamProviderException(
                exception.code,
                exception.message ?: "Withings OAuth token exchange failed",
                502,
            )
        }

        val providerUserId = tokens.providerUserId.takeIf { it.isNotBlank() }
            ?: throw UpstreamProviderException(
                "withings_missing_userid",
                "Withings OAuth token response did not include userid",
                502,
            )
        val providerInstanceId = providerInstanceId(providerUserId)
        val cipher = TokenCipher(config.tokenEncryptionKey)
        repository.upsertAccount(
            providerCode = WITHINGS_PROVIDER_CODE,
            providerUserId = providerUserId,
            providerInstanceId = providerInstanceId,
            accessTokenCiphertext = cipher.encrypt(tokens.accessToken),
            refreshTokenCiphertext = cipher.encrypt(tokens.refreshToken),
            tokenType = tokens.tokenType,
            expiresAt = tokens.expiresAt,
            scope = tokens.scope,
            now = now,
        )
        logger.info(
            "provider_oauth_connected {} {} {} {}",
            kv("provider", WITHINGS_PROVIDER_CODE),
            kv("providerInstanceId", providerInstanceId),
            kv("expiresAt", tokens.expiresAt.toString()),
            kv("scopeCount", tokens.scope.split(",").count { it.isNotBlank() }),
        )
        return ProviderConnection(
            providerCode = WITHINGS_PROVIDER_CODE,
            providerInstanceId = providerInstanceId,
            connected = true,
        )
    }

    override suspend fun sync(request: ProviderSyncRequest, now: Instant): ProviderSyncSummary {
        throw ConflictException(
            "withings_sync_not_implemented",
            "Withings data sync is not implemented yet",
        )
    }

    private fun requireConfigured() {
        val issues = buildList {
            if (config.clientId.isBlank()) add(ValidationIssue("withings.clientId"))
            if (config.clientSecret.isBlank()) add(ValidationIssue("withings.clientSecret"))
            if (config.redirectUri.isBlank()) add(ValidationIssue("withings.redirectUri"))
            if (config.tokenEncryptionKey.isBlank()) add(ValidationIssue("withings.tokenEncryptionKey"))
        }
        if (issues.isNotEmpty()) throw RequestValidationException(issues)
    }

    private fun providerInstanceId(providerUserId: String): String = "withings-$providerUserId"
}
