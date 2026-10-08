package me.aquitano.external.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

const val GOOGLE_HEALTH_PROVIDER_CODE = "google_health"

const val GOOGLE_HEALTH_DISPLAY_NAME = "Google Health"

val GOOGLE_HEALTH_SCOPES =
    listOf(
        "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly",
        "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly",
        "https://www.googleapis.com/auth/googlehealth.sleep.readonly",
    )

enum class GoogleHealthDataType(
    val code: String,
    private val filterField: String,
    val maxPageSize: Int = 10_000,
) {
    Steps("steps", "steps.interval.start_time"),
    Sleep("sleep", "sleep.interval.end_time", maxPageSize = 25),
    HeartRate("heart-rate", "heart_rate.sample_time.physical_time"),
    Weight("weight", "weight.sample_time.physical_time"),
    BodyFat("body-fat", "body_fat.sample_time.physical_time"),
    HeartRateVariability("heart-rate-variability", "heart_rate_variability.sample_time.physical_time"),
    RespiratoryRateSleepSummary("respiratory-rate-sleep-summary", "respiratory_rate_sleep_summary.sample_time.physical_time"),
    ;

    fun filter(
        from: Instant,
        to: Instant,
    ): String = """$filterField >= "$from" AND $filterField < "$to""""

    companion object {
        val codes: List<String> = entries.map { it.code }

        fun fromCode(code: String): GoogleHealthDataType = entries.first { it.code == code }
    }
}

data class GoogleHealthPage(
    val pageIndex: Int,
    val payload: JsonObject,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("pageIndex", pageIndex)
            put("payload", payload)
        }
}

data class GoogleHealthFetchResult(
    val dataType: GoogleHealthDataType,
    val pages: List<GoogleHealthPage>,
    val dataPoints: List<JsonObject>,
)

class GoogleHealthUnauthorizedException(
    message: String,
) : RuntimeException(message)

class GoogleHealthHttpException(
    val code: String,
    message: String,
    val oauthError: String? = null,
) : RuntimeException(message)
