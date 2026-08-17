package me.aquitano.external.google

import kotlinx.serialization.json.JsonObject
import me.aquitano.health.api.dto.IngestionRecord
import java.time.Instant

const val GOOGLE_HEALTH_PROVIDER_CODE = "google_health"

val GOOGLE_HEALTH_SCOPES = listOf(
    "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly",
    "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly",
    "https://www.googleapis.com/auth/googlehealth.sleep.readonly",
)

val GOOGLE_HEALTH_DEFAULT_DATA_TYPES = listOf(
    "steps",
    "sleep",
    "heart-rate",
    "weight",
    "body-fat",
)

data class GoogleHealthPage(
    val pageIndex: Int,
    val payload: JsonObject,
)

data class GoogleHealthFetchResult(
    val dataType: String,
    val pages: List<GoogleHealthPage>,
    val dataPoints: List<JsonObject>,
)

class GoogleHealthUnauthorizedException(message: String) :
    RuntimeException(message)

class GoogleHealthHttpException(
    val code: String,
    message: String,
    val oauthError: String? = null,
) : RuntimeException(message)
