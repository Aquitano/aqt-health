package me.aquitano.external.withings

import me.aquitano.health.application.providersync.RefreshedTokenSet
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant

const val WITHINGS_PROVIDER_CODE = "withings"

val WITHINGS_SCOPES = listOf(
    "user.info",
    "user.metrics",
    "user.activity",
)

val WITHINGS_DEFAULT_DATA_TYPES = listOf(
    "activity",
    "measures",
    "sleep-summary",
    "sleep",
)

/** Measure types the normalizer maps; anything else would be fetched and silently dropped. */
val WITHINGS_MEASURE_TYPES = listOf(
    1, 5, 6, 8, 9, 10, 11, 76, 77, 88, 91, 130, 135,
    136, 137, 138, 139, 155, 170, 173,
)

val WITHINGS_ACTIVITY_FIELDS = listOf(
    "steps",
    "distance",
    "elevation",
    "soft",
    "moderate",
    "intense",
    "active",
    "calories",
    "totalcalories",
    "hr_average",
    "hr_min",
    "hr_max",
)

val WITHINGS_SLEEP_FIELDS = listOf(
    "hr",
    "rr",
    "rmssd",
)

/**
 * How far before a sync window's start the `sleep` fetch reaches back.
 *
 * Sleep windows are UTC days, so a night that starts in the evening of the previous day would
 * otherwise arrive cut in half. Fetching the extra day makes such a night arrive whole; the
 * normalizer then keeps only the sessions that end inside the window, so each night is stored once,
 * by the window holding its end.
 */
val WITHINGS_SLEEP_LOOKBEHIND: Duration = Duration.ofDays(1)

val WITHINGS_SLEEP_SUMMARY_FIELDS = listOf(
    "total_timeinbed",
    "total_sleep_time",
    "asleepduration",
    "lightsleepduration",
    "remsleepduration",
    "deepsleepduration",
    "sleep_efficiency",
    "sleep_latency",
    "durationtosleep",
    "wakeup_latency",
    "durationtowakeup",
    "wakeupduration",
    "wakeupcount",
    "waso",
    "nb_rem_episodes",
    "out_of_bed_count",
    "apnea_hypopnea_index",
    "hr_average",
    "hr_min",
    "hr_max",
    "rr_average",
    "rr_min",
    "rr_max",
    "breathing_quality_assessment",
    "snoring",
    "snoringepisodecount",
    "sleep_score",
    "mvt_score_avg",
    "rmssd_start_avg",
    "chest_movement_rate_wellness_average",
)

data class WithingsTokenSet(
    val providerUserId: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresAt: Instant,
    val scope: String,
) {
    fun toRefreshedTokenSet(): RefreshedTokenSet =
        RefreshedTokenSet(
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenType = tokenType,
            expiresAt = expiresAt,
            scope = scope,
        )
}

data class WithingsPage(
    val endpoint: String,
    val action: String,
    val pageIndex: Int,
    val payload: JsonObject,
)

data class WithingsFetchResult(
    val dataType: String,
    val pages: List<WithingsPage>,
    val records: List<JsonObject>,
)

class WithingsHttpException(
    val code: String,
    message: String,
    val providerStatus: Int? = null,
    val providerAction: String? = null,
    val providerEndpoint: String? = null,
) : RuntimeException(message)
