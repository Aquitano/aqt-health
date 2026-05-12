package me.aquitano.health.infrastructure.providers.withings

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.aquitano.health.infrastructure.config.WithingsConfig
import me.aquitano.health.infrastructure.providers.withings.generated.model.Oauth2Getaccesstoken200Response
import me.aquitano.health.shared.AppJson
import java.time.Instant
import java.time.ZoneOffset

interface WithingsClient {
    suspend fun exchangeCode(code: String, now: Instant): WithingsTokenSet
    suspend fun refreshToken(refreshToken: String, now: Instant): WithingsTokenSet
    suspend fun fetch(dataType: String, accessToken: String, from: Instant, to: Instant): WithingsFetchResult
}

class KtorWithingsClient(
    private val httpClient: HttpClient,
    private val config: WithingsConfig,
) : WithingsClient {
    private val apiBaseUrl = config.apiBaseUrl.trimEnd('/')

    override suspend fun exchangeCode(code: String, now: Instant): WithingsTokenSet {
        val response = httpClient.submitForm(
            url = "$apiBaseUrl/v2/oauth2",
            formParameters = parameters {
                append("action", "requesttoken")
                append("grant_type", "authorization_code")
                append("client_id", config.clientId)
                append("client_secret", config.clientSecret)
                append("code", code)
                append("redirect_uri", config.redirectUri)
            },
        )
        return parseTokenResponse(response.status, response.body(), now, existingRefreshToken = null)
    }

    override suspend fun refreshToken(refreshToken: String, now: Instant): WithingsTokenSet {
        val response = httpClient.submitForm(
            url = "$apiBaseUrl/v2/oauth2",
            formParameters = parameters {
                append("action", "requesttoken")
                append("grant_type", "refresh_token")
                append("client_id", config.clientId)
                append("client_secret", config.clientSecret)
                append("refresh_token", refreshToken)
            },
        )
        return parseTokenResponse(response.status, response.body(), now, existingRefreshToken = refreshToken)
    }

    override suspend fun fetch(
        dataType: String,
        accessToken: String,
        from: Instant,
        to: Instant,
    ): WithingsFetchResult =
        when (val endpoint = WithingsEndpoint.fromDataType(dataType)) {
            null -> throw WithingsHttpException(
                "withings_unsupported_data_type",
                "Unsupported Withings data type: $dataType",
            )
            else -> fetchPaged(endpoint, accessToken, from, to)
        }

    private enum class WithingsEndpoint(
        val dataType: String,
        val path: String,
        val action: String,
    ) {
        Activity(WITHINGS_ACTIVITY_DATA_TYPE, "/v2/measure", "getactivity"),
        BodyMeasurements(WITHINGS_BODY_MEASUREMENTS_DATA_TYPE, "/measure", "getmeas"),
        SleepSummary(WITHINGS_SLEEP_SUMMARY_DATA_TYPE, "/v2/sleep", "getsummary");

        companion object {
            fun fromDataType(dataType: String): WithingsEndpoint? =
                entries.firstOrNull { it.dataType == dataType }
        }
    }

    private suspend fun fetchPaged(
        endpoint: WithingsEndpoint,
        accessToken: String,
        from: Instant,
        to: Instant,
    ): WithingsFetchResult {
        val pages = mutableListOf<JsonObject>()
        var nextOffset: Int? = null
        do {
            val page = fetchPage(endpoint, accessToken, from, to, nextOffset)
            pages.add(page.payload)
            nextOffset = page.nextOffset
        } while (nextOffset != null)

        return WithingsFetchResult(endpoint.dataType, pages)
    }

    private suspend fun fetchPage(
        endpoint: WithingsEndpoint,
        accessToken: String,
        from: Instant,
        to: Instant,
        offset: Int?,
    ): WithingsPage {
        val response = httpClient.submitForm(
            url = "$apiBaseUrl${endpoint.path}",
            formParameters = parameters {
                append("action", endpoint.action)
                appendProviderParams(endpoint, from, to)
                offset?.let { append("offset", it.toString()) }
            },
        ) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            accept(ContentType.Application.Json)
        }
        val text = response.body<String>()
        if (response.status == HttpStatusCode.Unauthorized) {
            throw WithingsUnauthorizedException("Withings access token is unauthorized")
        }
        if (!response.status.isSuccess()) {
            throw WithingsHttpException(
                "withings_upstream_failed",
                "Withings ${endpoint.dataType} request failed with ${response.status.value}",
            )
        }

        val payload = AppJson.parseToJsonElement(text).jsonObject
        val withingsStatus = payload.intOrNull("status") ?: 0
        if (withingsStatus != 0) {
            throw WithingsHttpException(
                "withings_upstream_failed",
                "Withings ${endpoint.dataType} response status was $withingsStatus",
            )
        }
        val responseBody = payload["body"]?.jsonObject
        val hasMore = responseBody?.booleanLike("more") ?: false
        return WithingsPage(
            payload = payload,
            nextOffset = if (hasMore) responseBody.intOrNull("offset") else null,
        )
    }

    private data class WithingsPage(
        val payload: JsonObject,
        val nextOffset: Int?,
    )

    private fun ParametersBuilder.appendProviderParams(endpoint: WithingsEndpoint, from: Instant, to: Instant) {
        when (endpoint) {
            WithingsEndpoint.Activity -> {
                append("startdateymd", from.atZone(ZoneOffset.UTC).toLocalDate().toString())
                append("enddateymd", to.minusSeconds(1).atZone(ZoneOffset.UTC).toLocalDate().toString())
                append("data_fields", "steps,hr_average")
            }
            WithingsEndpoint.BodyMeasurements -> {
                append("startdate", from.epochSecond.toString())
                append("enddate", to.epochSecond.toString())
                append("meastypes", "1,6,11")
                append("category", "1")
            }
            WithingsEndpoint.SleepSummary -> {
                append("startdateymd", from.atZone(ZoneOffset.UTC).toLocalDate().toString())
                append("enddateymd", to.minusSeconds(1).atZone(ZoneOffset.UTC).toLocalDate().toString())
                append("data_fields", "total_sleep_time,wakeupduration,lightsleepduration,deepsleepduration,remsleepduration")
            }
        }
    }

    private fun parseTokenResponse(
        status: HttpStatusCode,
        text: String,
        now: Instant,
        existingRefreshToken: String?,
    ): WithingsTokenSet {
        if (!status.isSuccess()) {
            val code = if (existingRefreshToken == null) "withings_token_exchange_failed" else "withings_token_refresh_failed"
            throw WithingsHttpException(code, "Withings OAuth token request failed with ${status.value}")
        }
        val response = AppJson.decodeFromString<Oauth2Getaccesstoken200Response>(text)
        if (response.status != 0) {
            throw WithingsHttpException("withings_token_exchange_failed", "Withings OAuth token response status was ${response.status}")
        }
        val body = response.body
            ?: throw WithingsHttpException("withings_token_exchange_failed", "Withings OAuth token response did not include body")
        val accessToken = body.accessToken
            ?: throw WithingsHttpException("withings_token_exchange_failed", "Withings OAuth token response did not include access_token")
        val refreshToken = body.refreshToken ?: existingRefreshToken
        return WithingsTokenSet(
            providerUserId = body.userid?.toString() ?: "me",
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenType = body.tokenType ?: "Bearer",
            expiresAt = now.plusSeconds((body.expiresIn ?: 10800).toLong()),
            scope = body.scope ?: WITHINGS_SCOPES.joinToString(","),
        )
    }

    private fun JsonObject.intOrNull(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.booleanLike(key: String): Boolean? =
        this[key]?.jsonPrimitive?.let { primitive ->
            primitive.booleanOrNull ?: primitive.intOrNull?.let { it != 0 }
        }
}
