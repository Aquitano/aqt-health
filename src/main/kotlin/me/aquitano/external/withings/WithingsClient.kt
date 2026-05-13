package me.aquitano.external.withings

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.aquitano.health.infrastructure.config.WithingsConfig
import me.aquitano.health.shared.AppJson
import java.time.Instant

interface WithingsOAuthClient {
    suspend fun exchangeCode(code: String, now: Instant): WithingsTokenSet
    suspend fun refreshToken(refreshToken: String, now: Instant): WithingsTokenSet
}

class KtorWithingsOAuthClient(
    private val httpClient: HttpClient,
    private val config: WithingsConfig,
) : WithingsOAuthClient {
    override suspend fun exchangeCode(code: String, now: Instant): WithingsTokenSet {
        val response = httpClient.submitForm(
            url = config.oauthTokenUrl,
            formParameters = parameters {
                append("action", "requesttoken")
                append("grant_type", "authorization_code")
                append("client_id", config.clientId)
                append("client_secret", config.clientSecret)
                append("code", code)
                append("redirect_uri", config.redirectUri)
            },
        )
        return parseTokenResponse(
            status = response.status,
            text = response.body(),
            now = now,
            existingRefreshToken = null,
            requireUserId = true,
        )
    }

    override suspend fun refreshToken(refreshToken: String, now: Instant): WithingsTokenSet {
        val response = httpClient.submitForm(
            url = config.oauthTokenUrl,
            formParameters = parameters {
                append("action", "requesttoken")
                append("grant_type", "refresh_token")
                append("client_id", config.clientId)
                append("client_secret", config.clientSecret)
                append("refresh_token", refreshToken)
            },
        )
        return parseTokenResponse(
            status = response.status,
            text = response.body(),
            now = now,
            existingRefreshToken = refreshToken,
            requireUserId = false,
        )
    }

    private fun parseTokenResponse(
        status: HttpStatusCode,
        text: String,
        now: Instant,
        existingRefreshToken: String?,
        requireUserId: Boolean,
    ): WithingsTokenSet {
        if (!status.isSuccess()) {
            throw WithingsHttpException(
                "withings_token_request_failed",
                "Withings OAuth token request failed with ${status.value}",
            )
        }

        val payload = AppJson.parseToJsonElement(text).jsonObject
        val withingsStatus = payload["status"]?.jsonPrimitive?.intOrNull
        if (withingsStatus != 0) {
            throw WithingsHttpException(
                "withings_token_request_failed",
                "Withings OAuth token request failed with status ${withingsStatus ?: "missing"}",
            )
        }

        val body = payload["body"]?.jsonObject
            ?: throw WithingsHttpException("withings_token_request_failed", "Withings OAuth token response did not include body")
        val accessToken = body.stringOrNull("access_token")
            ?: throw WithingsHttpException("withings_missing_access_token", "Withings OAuth token response did not include access_token")
        val refreshToken = body.stringOrNull("refresh_token") ?: existingRefreshToken
            ?: throw WithingsHttpException("withings_missing_refresh_token", "Withings OAuth token response did not include refresh_token")
        val providerUserId = body["userid"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (requireUserId && providerUserId.isBlank()) {
            throw WithingsHttpException("withings_missing_userid", "Withings OAuth token response did not include userid")
        }
        val tokenType = body.stringOrNull("token_type") ?: "Bearer"
        val expiresIn = body["expires_in"]?.jsonPrimitive?.longOrNull ?: 10800L
        val scope = body.stringOrNull("scope") ?: WITHINGS_SCOPES.joinToString(",")

        return WithingsTokenSet(
            providerUserId = providerUserId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenType = tokenType,
            expiresAt = now.plusSeconds(expiresIn),
            scope = scope,
        )
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
}
