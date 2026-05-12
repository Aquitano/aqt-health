package me.aquitano.health.infrastructure.providers.withings

import io.ktor.http.URLBuilder
import me.aquitano.health.api.dto.WithingsOAuthCallbackResponse
import me.aquitano.health.api.dto.WithingsOAuthStartResponse
import me.aquitano.health.domain.RequestValidationException
import me.aquitano.health.domain.UpstreamProviderException
import me.aquitano.health.domain.ValidationIssue
import me.aquitano.health.infrastructure.config.WithingsConfig
import me.aquitano.health.infrastructure.repositories.ProviderOAuthRepository
import me.aquitano.health.infrastructure.security.TokenCipher
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

class WithingsOAuthService(
    private val config: WithingsConfig,
    private val repository: ProviderOAuthRepository,
    private val client: WithingsClient,
) {
    private val random = SecureRandom()

    suspend fun start(now: Instant): WithingsOAuthStartResponse {
        requireConfigured()
        val state = randomState()
        val expiresAt = now.plus(Duration.ofMinutes(10))
        repository.insertState(state, WITHINGS_PROVIDER_CODE, now, expiresAt)
        val url = URLBuilder(config.oauthAuthUrl).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", config.clientId)
            parameters.append("redirect_uri", config.redirectUri)
            parameters.append("scope", WITHINGS_SCOPES.joinToString(","))
            parameters.append("state", state)
        }.buildString()
        return WithingsOAuthStartResponse(url, expiresAt.toString())
    }

    suspend fun callback(code: String?, state: String?, error: String?, now: Instant): WithingsOAuthCallbackResponse {
        requireConfigured()
        if (!error.isNullOrBlank()) {
            throw RequestValidationException(listOf(ValidationIssue("error", error)))
        }
        val authCode = code?.takeIf { it.isNotBlank() }
        val authState = state?.takeIf { it.isNotBlank() }
        if (authCode == null || authState == null) {
            throw RequestValidationException(
                listOf(
                    ValidationIssue("code", "is required"),
                    ValidationIssue("state", "is required"),
                )
            )
        }
        val storedState = repository.consumeState(authState, WITHINGS_PROVIDER_CODE, now)
            ?: throw RequestValidationException(listOf(ValidationIssue("state", "is invalid")))
        if (storedState.consumedAt != null) {
            throw RequestValidationException(listOf(ValidationIssue("state", "was already used")))
        }
        if (!now.isBefore(storedState.expiresAt)) {
            throw RequestValidationException(listOf(ValidationIssue("state", "has expired")))
        }

        val tokens = providerCall("withings_token_exchange_failed", "Withings OAuth token exchange failed") {
            client.exchangeCode(authCode, now)
        }
        val refreshToken = tokens.refreshToken
            ?: throw UpstreamProviderException(
                "withings_missing_refresh_token",
                "Withings OAuth response did not include a refresh token",
                502,
            )
        val providerInstanceId = "withings-${tokens.providerUserId}"
        val cipher = TokenCipher(config.tokenEncryptionKey)
        repository.upsertAccount(
            providerCode = WITHINGS_PROVIDER_CODE,
            providerUserId = tokens.providerUserId,
            providerInstanceId = providerInstanceId,
            accessTokenCiphertext = cipher.encrypt(tokens.accessToken),
            refreshTokenCiphertext = cipher.encrypt(refreshToken),
            tokenType = tokens.tokenType,
            expiresAt = tokens.expiresAt,
            scope = tokens.scope,
            now = now,
        )
        return WithingsOAuthCallbackResponse(
            provider = WITHINGS_PROVIDER_CODE,
            providerInstanceId = providerInstanceId,
            connected = true,
        )
    }

    private fun requireConfigured() {
        val issues = buildList {
            if (config.clientId.isBlank()) add(ValidationIssue("withings.clientId", "is required"))
            if (config.clientSecret.isBlank()) add(ValidationIssue("withings.clientSecret", "is required"))
            if (config.redirectUri.isBlank()) add(ValidationIssue("withings.redirectUri", "is required"))
            if (config.tokenEncryptionKey.isBlank()) add(ValidationIssue("withings.tokenEncryptionKey", "is required"))
        }
        if (issues.isNotEmpty()) throw RequestValidationException(issues)
    }

    private fun randomState(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private suspend fun <T> providerCall(
        fallbackCode: String,
        fallbackMessage: String,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (throwable: WithingsHttpException) {
            throw UpstreamProviderException(throwable.code, throwable.message ?: fallbackMessage, 502)
        } catch (throwable: WithingsUnauthorizedException) {
            throw UpstreamProviderException(fallbackCode, throwable.message ?: fallbackMessage, 502)
        }
}
