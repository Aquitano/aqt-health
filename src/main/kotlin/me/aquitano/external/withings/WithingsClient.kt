package me.aquitano.external.withings

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import me.aquitano.health.infrastructure.config.ProviderOAuthConfig
import me.aquitano.health.shared.AppJson
import me.aquitano.health.shared.formParameters
import me.aquitano.health.shared.stringOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

interface WithingsClient {
    suspend fun exchangeCode(
        code: String,
        now: Instant,
    ): WithingsTokenSet

    suspend fun refreshToken(
        refreshToken: String,
        now: Instant,
    ): WithingsTokenSet

    suspend fun fetchMeasures(
        accessToken: String,
        from: Instant,
        to: Instant,
        measureTypes: List<Int>,
        category: Int,
    ): WithingsFetchResult

    suspend fun fetchActivity(
        accessToken: String,
        from: Instant,
        to: Instant,
        dataFields: List<String>,
    ): WithingsFetchResult

    suspend fun fetchSleep(
        accessToken: String,
        from: Instant,
        to: Instant,
        dataFields: List<String>,
    ): WithingsFetchResult

    suspend fun fetchSleepSummary(
        accessToken: String,
        from: Instant,
        to: Instant,
        dataFields: List<String>,
    ): WithingsFetchResult
}

internal const val MAX_WITHINGS_PAGES = 500

class KtorWithingsClient(
    private val httpClient: HttpClient,
    private val config: ProviderOAuthConfig,
) : WithingsClient {
    override suspend fun exchangeCode(
        code: String,
        now: Instant,
    ): WithingsTokenSet {
        val action = "requesttoken"
        val response =
            httpClient.submitForm(
                url = config.oauthTokenUrl,
                formParameters =
                    formParameters(
                        "action" to action,
                        "grant_type" to "authorization_code",
                        "client_id" to config.clientId,
                        "client_secret" to config.clientSecret,
                        "code" to code,
                        "redirect_uri" to config.redirectUri,
                    ),
            )
        return parseTokenResponse(
            status = response.status,
            text = response.body(),
            now = now,
            existingRefreshToken = null,
            requireUserId = true,
        )
    }

    override suspend fun refreshToken(
        refreshToken: String,
        now: Instant,
    ): WithingsTokenSet {
        val action = "requesttoken"
        val response =
            httpClient.submitForm(
                url = config.oauthTokenUrl,
                formParameters =
                    formParameters(
                        "action" to action,
                        "grant_type" to "refresh_token",
                        "client_id" to config.clientId,
                        "client_secret" to config.clientSecret,
                        "refresh_token" to refreshToken,
                    ),
            )
        return parseTokenResponse(
            status = response.status,
            text = response.body(),
            now = now,
            existingRefreshToken = refreshToken,
            requireUserId = false,
        )
    }

    override suspend fun fetchMeasures(
        accessToken: String,
        from: Instant,
        to: Instant,
        measureTypes: List<Int>,
        category: Int,
    ): WithingsFetchResult =
        fetchPaged(
            accessToken = accessToken,
            dataType = WithingsDataType.Measures,
            endpoint = measureEndpoint(),
            action = "getmeas",
            recordsKey = "measuregrps",
            baseParameters =
                listOf(
                    "meastypes" to measureTypes.joinToString(","),
                    "category" to category.toString(),
                    "startdate" to from.epochSecond.toString(),
                    "enddate" to inclusiveEndSeconds(from, to).toString(),
                ),
        )

    override suspend fun fetchActivity(
        accessToken: String,
        from: Instant,
        to: Instant,
        dataFields: List<String>,
    ): WithingsFetchResult {
        val (startYmd, endYmd) = ymdRange(from, to)
        return fetchPaged(
            accessToken = accessToken,
            dataType = WithingsDataType.Activity,
            endpoint = measureEndpoint(),
            action = "getactivity",
            recordsKey = "activities",
            baseParameters =
                listOf(
                    "startdateymd" to startYmd.toString(),
                    "enddateymd" to endYmd.toString(),
                    "data_fields" to dataFields.joinToString(","),
                ),
        )
    }

    // Sleep v2 `get` silently returns only the first 24h of a longer range, so the range is
    // fetched in 24h chunks. A segment crossing a chunk edge can come back twice.
    override suspend fun fetchSleep(
        accessToken: String,
        from: Instant,
        to: Instant,
        dataFields: List<String>,
    ): WithingsFetchResult {
        val chunks =
            generateSequence(from) { it.plus(WITHINGS_SLEEP_GET_MAX_RANGE) }
                .takeWhile { it.isBefore(to) }
                .toList()
                .map { start ->
                    val end = minOf(start.plus(WITHINGS_SLEEP_GET_MAX_RANGE), to)
                    fetchPaged(
                        accessToken = accessToken,
                        dataType = WithingsDataType.Sleep,
                        endpoint = sleepEndpoint(),
                        action = "get",
                        recordsKey = "series",
                        baseParameters =
                            listOf(
                                "startdate" to start.epochSecond.toString(),
                                "enddate" to inclusiveEndSeconds(start, end).toString(),
                                "data_fields" to dataFields.joinToString(","),
                            ),
                    )
                }
        return WithingsFetchResult(
            dataType = WithingsDataType.Sleep,
            pages = chunks.flatMap { it.pages },
            records = chunks.flatMap { it.records }.distinct(),
        )
    }

    override suspend fun fetchSleepSummary(
        accessToken: String,
        from: Instant,
        to: Instant,
        dataFields: List<String>,
    ): WithingsFetchResult {
        val (startYmd, endYmd) = ymdRange(from, to)
        return fetchPaged(
            accessToken = accessToken,
            dataType = WithingsDataType.SleepSummary,
            endpoint = sleepEndpoint(),
            action = "getsummary",
            recordsKey = "series",
            baseParameters =
                listOf(
                    "startdateymd" to startYmd.toString(),
                    "enddateymd" to endYmd.toString(),
                    "data_fields" to dataFields.joinToString(","),
                ),
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
                httpStatus = status.value,
                providerAction = "requesttoken",
                providerEndpoint = config.oauthTokenUrl,
            )
        }

        val payload = AppJson.parseToJsonElement(text).jsonObject
        val withingsStatus = payload["status"]?.jsonPrimitive?.intOrNull
        if (withingsStatus != 0) {
            throw WithingsHttpException(
                "withings_token_request_failed",
                "Withings OAuth token request failed with status ${withingsStatus ?: "missing"}",
                providerStatus = withingsStatus,
                providerAction = "requesttoken",
                providerEndpoint = config.oauthTokenUrl,
            )
        }

        val body =
            payload["body"]?.jsonObject
                ?: throw WithingsHttpException(
                    "withings_token_request_failed",
                    "Withings OAuth token response did not include body",
                    providerAction = "requesttoken",
                    providerEndpoint = config.oauthTokenUrl,
                )
        val accessToken =
            body.stringOrNull("access_token")
                ?: throw WithingsHttpException(
                    "withings_missing_access_token",
                    "Withings OAuth token response did not include access_token",
                    providerAction = "requesttoken",
                    providerEndpoint = config.oauthTokenUrl,
                )
        val refreshToken =
            body.stringOrNull("refresh_token") ?: existingRefreshToken
                ?: throw WithingsHttpException(
                    "withings_missing_refresh_token",
                    "Withings OAuth token response did not include refresh_token",
                    providerAction = "requesttoken",
                    providerEndpoint = config.oauthTokenUrl,
                )
        val providerUserId =
            body["userid"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (requireUserId && providerUserId.isBlank()) {
            throw WithingsHttpException(
                "withings_missing_userid",
                "Withings OAuth token response did not include userid",
                providerAction = "requesttoken",
                providerEndpoint = config.oauthTokenUrl,
            )
        }
        val tokenType = body.stringOrNull("token_type") ?: "Bearer"
        val expiresIn = body["expires_in"]?.jsonPrimitive?.longOrNull ?: 10800L
        val scope =
            body.stringOrNull("scope") ?: WITHINGS_SCOPES.joinToString(",")

        return WithingsTokenSet(
            providerUserId = providerUserId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenType = tokenType,
            expiresAt = now.plusSeconds(expiresIn),
            scope = scope,
        )
    }

    private suspend fun fetchPaged(
        accessToken: String,
        dataType: WithingsDataType,
        endpoint: String,
        action: String,
        recordsKey: String,
        baseParameters: List<Pair<String, String>>,
    ): WithingsFetchResult {
        val pages = mutableListOf<WithingsPage>()
        val records = mutableListOf<JsonObject>()
        val seenOffsets = mutableSetOf<String>()
        var offset: String? = null
        var pageIndex = 0

        do {
            if (pageIndex >= MAX_WITHINGS_PAGES) {
                throw WithingsHttpException(
                    "withings_page_limit_exceeded",
                    "Withings $action pagination exceeded $MAX_WITHINGS_PAGES pages",
                    providerAction = action,
                    providerEndpoint = endpoint,
                )
            }
            val response =
                httpClient.submitForm(
                    url = endpoint,
                    formParameters =
                        formParameters(
                            buildList {
                                add("action" to action)
                                addAll(baseParameters)
                                offset?.let { add("offset" to it) }
                            },
                        ),
                ) {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }

            val payload =
                parseDataResponse(action, endpoint, response.status, response.body())
            val body =
                payload["body"]?.jsonObject
                    ?: throw WithingsHttpException(
                        "withings_malformed_response",
                        "Withings $action response did not include body",
                        providerAction = action,
                        providerEndpoint = endpoint,
                    )
            pages.add(WithingsPage(endpoint, action, pageIndex, payload))
            records.addAll(body.records(recordsKey))

            val nextOffset =
                body["offset"]?.jsonPrimitive?.contentOrNull
                    ?: body["offset"]?.jsonPrimitive?.longOrNull?.toString()
            val hasMore =
                body["more"]?.jsonPrimitive?.booleanOrNull
                    ?: body["more"]?.jsonPrimitive?.intOrNull?.let { it == 1 }
                    ?: false
            offset =
                if (hasMore) {
                    if (nextOffset.isNullOrBlank()) {
                        throw WithingsHttpException(
                            "withings_malformed_response",
                            "Withings $action response did not include next offset",
                            providerAction = action,
                            providerEndpoint = endpoint,
                        )
                    }
                    if (!seenOffsets.add(nextOffset)) {
                        throw WithingsHttpException(
                            "withings_pagination_loop",
                            "Withings $action returned a repeated offset",
                            providerAction = action,
                            providerEndpoint = endpoint,
                        )
                    }
                    nextOffset
                } else {
                    null
                }
            pageIndex += 1
        } while (offset != null)

        return WithingsFetchResult(dataType, pages, records)
    }

    private fun parseDataResponse(
        action: String,
        endpoint: String,
        status: HttpStatusCode,
        text: String,
    ): JsonObject {
        if (!status.isSuccess()) {
            throw WithingsHttpException(
                "withings_data_request_failed",
                "Withings $action request failed with ${status.value}",
                httpStatus = status.value,
                providerAction = action,
                providerEndpoint = endpoint,
            )
        }
        val payload = AppJson.parseToJsonElement(text).jsonObject
        val withingsStatus = payload["status"]?.jsonPrimitive?.intOrNull
        if (withingsStatus != 0) {
            val detail = payload.withingsErrorDetail()
            throw WithingsHttpException(
                "withings_data_request_failed",
                "Withings $action request failed with status ${withingsStatus ?: "missing"}${
                    detail?.let { ": $it" }.orEmpty()
                }",
                providerStatus = withingsStatus,
                providerAction = action,
                providerEndpoint = endpoint,
            )
        }
        return payload
    }

    private fun JsonObject.withingsErrorDetail(): String? =
        stringOrNull("error")
            ?: (this["body"] as? JsonObject)?.stringOrNull("error")
            ?: (this["body"] as? JsonObject)?.stringOrNull("message")

    private fun JsonObject.records(key: String): List<JsonObject> =
        when (val element = this[key]) {
            is JsonArray -> {
                element.mapNotNull { it as? JsonObject }
            }

            is JsonObject -> {
                element.entries.map { (recordKey, value) ->
                    if (value is JsonObject) {
                        buildJsonObject {
                            put("timestamp", recordKey)
                            value.entries.forEach { (key, entryValue) ->
                                put(
                                    key,
                                    entryValue,
                                )
                            }
                        }
                    } else {
                        buildJsonObject {
                            put("timestamp", recordKey)
                            put("value", value)
                        }
                    }
                }
            }

            else -> {
                emptyList()
            }
        }

    // Withings accepts inclusive seconds. Subtract before truncating so fractional window ends
    // retain their last included second; clamp sub-second windows to their start second.
    private fun inclusiveEndSeconds(
        from: Instant,
        to: Instant,
    ): Long = maxOf(from.epochSecond, to.minusNanos(1).epochSecond)

    private fun ymdRange(
        from: Instant,
        to: Instant,
    ): Pair<LocalDate, LocalDate> =
        from.atZone(ZoneOffset.UTC).toLocalDate() to
            to
                .minusNanos(1)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()

    private fun measureEndpoint(): String = "${config.apiBaseUrl.trimEnd('/')}/v2/measure"

    private fun sleepEndpoint(): String = "${config.apiBaseUrl.trimEnd('/')}/v2/sleep"
}
