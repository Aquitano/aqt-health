package me.aquitano.health.domain

object DailyMetricTypes {
    const val RESTING_HEART_RATE = "resting_heart_rate"
    const val NON_REM_HEART_RATE = "non_rem_heart_rate"
    const val NIGHTLY_HRV_RMSSD = "nightly_hrv_rmssd"
    const val DEEP_SLEEP_HRV_RMSSD = "deep_sleep_hrv_rmssd"
    const val OXYGEN_SATURATION = "oxygen_saturation"
    const val OXYGEN_SATURATION_LOWER_BOUND = "oxygen_saturation_lower_bound"
    const val NIGHTLY_RESPIRATORY_RATE = "nightly_respiratory_rate"
    const val NIGHTLY_SKIN_TEMPERATURE = "nightly_skin_temperature"
    const val SKIN_TEMPERATURE_BASELINE = "skin_temperature_baseline"
}

data class DailyMetricDescriptor(
    val metricType: String,
    val unit: String,
    val valueRange: ScalarValueRange,
)

/** The provider picks the date; overnight metrics carry the date of the morning the night ended, matching /sleep/nights. */
object DailyMetricRegistry {
    val descriptors: List<DailyMetricDescriptor> =
        listOf(
            DailyMetricDescriptor(DailyMetricTypes.RESTING_HEART_RATE, "bpm", ScalarValueRange(25.0, 250.0)),
            DailyMetricDescriptor(DailyMetricTypes.NON_REM_HEART_RATE, "bpm", ScalarValueRange(25.0, 250.0)),
            DailyMetricDescriptor(DailyMetricTypes.NIGHTLY_HRV_RMSSD, "ms", ScalarValueRange(0.0, 500.0, minInclusive = false)),
            DailyMetricDescriptor(DailyMetricTypes.DEEP_SLEEP_HRV_RMSSD, "ms", ScalarValueRange(0.0, 500.0, minInclusive = false)),
            DailyMetricDescriptor(DailyMetricTypes.OXYGEN_SATURATION, "percent", ScalarValueRange(50.0, 100.0)),
            DailyMetricDescriptor(DailyMetricTypes.OXYGEN_SATURATION_LOWER_BOUND, "percent", ScalarValueRange(50.0, 100.0)),
            DailyMetricDescriptor(DailyMetricTypes.NIGHTLY_RESPIRATORY_RATE, "breaths_per_minute", ScalarValueRange(5.0, 80.0)),
            DailyMetricDescriptor(DailyMetricTypes.NIGHTLY_SKIN_TEMPERATURE, "celsius", ScalarValueRange(25.0, 45.0)),
            DailyMetricDescriptor(DailyMetricTypes.SKIN_TEMPERATURE_BASELINE, "celsius", ScalarValueRange(25.0, 45.0)),
        )

    private val byType = descriptors.associateBy { it.metricType }

    val metricTypes: Set<String> = byType.keys

    fun find(metricType: String): DailyMetricDescriptor? = byType[metricType]
}
