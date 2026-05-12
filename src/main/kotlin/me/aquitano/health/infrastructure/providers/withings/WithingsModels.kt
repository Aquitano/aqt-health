package me.aquitano.health.infrastructure.providers.withings

import kotlinx.serialization.json.JsonObject
import java.time.Instant

const val WITHINGS_PROVIDER_CODE = "withings"
const val WITHINGS_ACTIVITY_DATA_TYPE = "activity"
const val WITHINGS_BODY_MEASUREMENTS_DATA_TYPE = "body-measurements"
const val WITHINGS_SLEEP_SUMMARY_DATA_TYPE = "sleep-summary"

val WITHINGS_SCOPES = listOf(
    "user.info",
    "user.metrics",
    "user.activity",
    "user.sleepevents",
)

val WITHINGS_DEFAULT_DATA_TYPES = listOf(
    WITHINGS_ACTIVITY_DATA_TYPE,
    WITHINGS_BODY_MEASUREMENTS_DATA_TYPE,
    WITHINGS_SLEEP_SUMMARY_DATA_TYPE,
)

data class WithingsTokenSet(
    val providerUserId: String,
    val accessToken: String,
    val refreshToken: String?,
    val tokenType: String,
    val expiresAt: Instant,
    val scope: String,
)

data class WithingsFetchResult(
    val dataType: String,
    val pages: List<JsonObject>,
)

data class WithingsNormalizedBatch(
    val sourcePayload: JsonObject,
    val records: List<JsonObject>,
)

class WithingsUnauthorizedException(message: String) : RuntimeException(message)

class WithingsHttpException(
    val code: String,
    message: String,
) : RuntimeException(message)
