package me.aquitano.external.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

const val GOOGLE_HEALTH_PROVIDER_CODE = "google_health"

const val GOOGLE_HEALTH_DISPLAY_NAME = "Google Health"

val GOOGLE_HEALTH_SCOPES =
    listOf(
        "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly",
        "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly",
        "https://www.googleapis.com/auth/googlehealth.sleep.readonly",
    )

/** How a data type is read: reconciled points filtered by time or by date, or merged daily roll-ups. */
sealed interface GoogleHealthFetch {
    /** `reconcile` merges every source into one stream, so overlapping devices are not double counted. */
    data class Reconcile(
        val filterField: String,
        val byDate: Boolean = false,
    ) : GoogleHealthFetch {
        fun filter(
            from: Instant,
            to: Instant,
        ): String =
            if (byDate) {
                val (fromDate, toDate) = civilDates(from, to)
                """$filterField >= "$fromDate" AND $filterField < "$toDate""""
            } else {
                """$filterField >= "$from" AND $filterField < "$to""""
            }
    }

    /** Daily roll-ups merged into one record per date, so no field lands in a separate row. */
    data object ActivityRollUp : GoogleHealthFetch {
        val rollUpTypes = listOf("distance", "total-calories", "active-minutes", "heart-rate")
    }
}

enum class GoogleHealthDataType(
    val code: String,
    val fetch: GoogleHealthFetch,
    val maxPageSize: Int = 10_000,
) {
    Steps("steps", GoogleHealthFetch.Reconcile("steps.interval.start_time")),
    Sleep("sleep", GoogleHealthFetch.Reconcile("sleep.interval.end_time"), maxPageSize = 25),
    HeartRate("heart-rate", GoogleHealthFetch.Reconcile("heart_rate.sample_time.physical_time")),
    Weight("weight", GoogleHealthFetch.Reconcile("weight.sample_time.physical_time")),
    BodyFat("body-fat", GoogleHealthFetch.Reconcile("body_fat.sample_time.physical_time")),
    HeartRateVariability("heart-rate-variability", GoogleHealthFetch.Reconcile("heart_rate_variability.sample_time.physical_time")),
    RespiratoryRateSleepSummary(
        "respiratory-rate-sleep-summary",
        GoogleHealthFetch.Reconcile("respiratory_rate_sleep_summary.sample_time.physical_time"),
    ),
    DailyRestingHeartRate("daily-resting-heart-rate", GoogleHealthFetch.Reconcile("daily_resting_heart_rate.date", byDate = true)),
    DailyHeartRateVariability("daily-heart-rate-variability", GoogleHealthFetch.Reconcile("daily_heart_rate_variability.date", byDate = true)),
    DailyOxygenSaturation("daily-oxygen-saturation", GoogleHealthFetch.Reconcile("daily_oxygen_saturation.date", byDate = true)),
    DailyRespiratoryRate("daily-respiratory-rate", GoogleHealthFetch.Reconcile("daily_respiratory_rate.date", byDate = true)),
    DailySleepTemperature(
        "daily-sleep-temperature-derivations",
        GoogleHealthFetch.Reconcile("daily_sleep_temperature_derivations.date", byDate = true),
    ),
    Activity("activity", GoogleHealthFetch.ActivityRollUp),
    ;

    companion object {
        val codes: List<String> = entries.map { it.code }

        fun fromCode(code: String): GoogleHealthDataType = entries.first { it.code == code }
    }
}

/**
 * The UTC dates a sync window covers, end exclusive. Windows are UTC-day aligned; the still-open
 * day ends before midnight and still covers its whole date.
 */
internal fun civilDates(
    from: Instant,
    to: Instant,
): Pair<LocalDate, LocalDate> =
    from.atOffset(ZoneOffset.UTC).toLocalDate() to
        to
            .minusNanos(1)
            .atOffset(ZoneOffset.UTC)
            .toLocalDate()
            .plusDays(1)

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
    cause: Throwable? = null,
) : RuntimeException(message, cause)
