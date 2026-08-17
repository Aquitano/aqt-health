package me.aquitano.external

import io.github.oshai.kotlinlogging.KotlinLogging
import me.aquitano.health.application.providersync.RefreshedTokenSet
import me.aquitano.health.domain.ProviderConnection
import me.aquitano.health.domain.ServerConfigurationException
import me.aquitano.health.domain.ValidationIssue
import me.aquitano.health.infrastructure.config.ProviderOAuthConfig
import me.aquitano.health.infrastructure.repositories.ProviderOAuthRepository
import me.aquitano.health.infrastructure.logging.infoWithContext
import me.aquitano.health.infrastructure.security.TokenCipher
import java.time.Instant

private val logger = KotlinLogging.logger {}

/** The config fields every OAuth provider needs before it can connect or sync. */
fun ProviderOAuthConfig.oauthConfigurationIssues(prefix: String): List<ValidationIssue> =
    buildList {
        if (clientId.isBlank()) add(ValidationIssue("$prefix.clientId"))
        if (clientSecret.isBlank()) add(ValidationIssue("$prefix.clientSecret"))
        if (redirectUri.isBlank()) add(ValidationIssue("$prefix.redirectUri"))
        if (tokenEncryptionKey.isBlank()) add(ValidationIssue("$prefix.tokenEncryptionKey"))
        if (apiBaseUrl.isBlank()) add(ValidationIssue("$prefix.apiBaseUrl"))
        if (oauthTokenUrl.isBlank()) add(ValidationIssue("$prefix.oauthTokenUrl"))
        if (oauthAuthUrl.isBlank()) add(ValidationIssue("$prefix.oauthAuthUrl"))
    }

fun requireProviderConfigured(notConfiguredCode: String, issues: List<ValidationIssue>) {
    if (issues.isNotEmpty()) {
        throw ServerConfigurationException(
            code = notConfiguredCode,
            publicMessage = "Provider is not configured",
            details = issues,
        )
    }
}

/** Encrypts and stores an OAuth token set after connect, logs it, and returns the connection. */
suspend fun persistOAuthConnection(
    repository: ProviderOAuthRepository,
    config: ProviderOAuthConfig,
    providerCode: String,
    providerUserId: String,
    providerInstanceId: String,
    tokens: RefreshedTokenSet,
    refreshToken: String,
    scopeDelimiter: String,
    now: Instant,
): ProviderConnection {
    val cipher = TokenCipher(config.tokenEncryptionKey)
    repository.upsertAccount(
        providerCode = providerCode,
        providerUserId = providerUserId,
        providerInstanceId = providerInstanceId,
        accessTokenCiphertext = cipher.encrypt(tokens.accessToken),
        refreshTokenCiphertext = cipher.encrypt(refreshToken),
        tokenType = tokens.tokenType,
        expiresAt = tokens.expiresAt,
        scope = tokens.scope.orEmpty(),
        now = now,
    )
    logger.infoWithContext(
        "provider_oauth_connected",
        "provider" to providerCode,
        "providerInstanceId" to providerInstanceId,
        "expiresAt" to tokens.expiresAt,
        "scopeCount" to tokens.scope.orEmpty().split(scopeDelimiter).count { it.isNotBlank() },
    )
    return ProviderConnection(
        providerCode = providerCode,
        providerInstanceId = providerInstanceId,
        connected = true,
    )
}
