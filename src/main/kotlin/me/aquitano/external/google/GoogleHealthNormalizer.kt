package me.aquitano.external.google

import kotlinx.serialization.json.*
import me.aquitano.health.api.dto.*
import me.aquitano.health.domain.BodyMetricTypes
import me.aquitano.health.domain.DailyMetricRegistry
import me.aquitano.health.domain.DailyMetricTypes
import me.aquitano.health.domain.ScalarMetricTypes
import me.aquitano.health.domain.roundToScalarPrecision
import me.aquitano.health.shared.AppJson
import me.aquitano.health.shared.doubleOrNull
import me.aquitano.health.shared.longOrNull
import me.aquitano.health.shared.objOrNull
import me.aquitano.health.shared.primitiveOrNull
import me.aquitano.health.shared.stringOrNull
import java.security.MessageDigest
import java.time.LocalDate
import java.util.HexFormat
import kotlin.math.roundToInt

class GoogleHealthNormalizer {
    fun normalize(fetchResult: GoogleHealthFetchResult): List<IngestionRecord> {
        // mainSleep is a plain proto boolean, so false and absent print the same. It only picks
        // the summary when the window has a main sleep at all; otherwise naps are excluded.
        val hasMainSleep = fetchResult.dataPoints.any { it.objOrNull("sleep")?.sleepMetadataFlag("mainSleep") == true }
        val records = fetchResult.dataPoints.flatMap { normalizeDataPoint(fetchResult.dataType, it, hasMainSleep) }
        val dateKeyed =
            when (val fetch = fetchResult.dataType.fetch) {
                is GoogleHealthFetch.Reconcile -> fetch.byDate
                GoogleHealthFetch.ActivityRollUp -> true
            }
        return if (dateKeyed) records else records.withDistinctTimeKeys("${fetchResult.dataType.code}:")
    }

    /**
     * The first record in content order keeps the plain time key, so a reading stored before a
     * second one arrived at its instant is updated rather than duplicated. Ordering by content, not
     * by arrival, gives a re-read of the window the same ids.
     */
    private fun List<IngestionRecord>.withDistinctTimeKeys(timeKeyPrefix: String): List<IngestionRecord> {
        val shared =
            filter { it.providerRecordId?.startsWith(timeKeyPrefix) == true }
                .groupBy { it.providerRecordId }
                .mapValues { (_, sharing) -> sharing.distinct() }
                .filterValues { it.size > 1 }
        if (shared.isEmpty()) return this
        val renamed =
            shared.values
                .flatMap { sharing ->
                    val contentByRecord = sharing.associateWith { AppJson.encodeToString(IngestionRecord.serializer(), it) }
                    contentByRecord.entries
                        .sortedBy { it.value }
                        .drop(1)
                        .map { (record, content) -> record to record.withProviderRecordId("${record.providerRecordId}:${contentKey(content)}") }
                }.toMap()
        return map { renamed[it] ?: it }
    }

    private fun contentKey(content: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))).take(16)

    private fun IngestionRecord.withProviderRecordId(id: String): IngestionRecord =
        when (this) {
            is StepInterval -> copy(providerRecordId = id)
            is SleepSession -> copy(providerRecordId = id)
            is SleepSummary -> copy(providerRecordId = id)
            is ActivitySummary -> copy(providerRecordId = id)
            is DailyMetric -> copy(providerRecordId = id)
            is BloodPressure -> copy(providerRecordId = id)
            is ScalarSample -> copy(providerRecordId = id)
        }

    private fun normalizeDataPoint(
        dataType: GoogleHealthDataType,
        point: JsonObject,
        hasMainSleep: Boolean,
    ): List<IngestionRecord> {
        val code = dataType.code
        return when (dataType) {
            GoogleHealthDataType.Steps -> listOfNotNull(normalizeSteps(code, point))

            GoogleHealthDataType.Sleep -> normalizeSleep(code, point, hasMainSleep)

            GoogleHealthDataType.HeartRate -> listOfNotNull(normalizeHeartRate(code, point))

            GoogleHealthDataType.Weight -> listOfNotNull(normalizeWeight(code, point))

            GoogleHealthDataType.BodyFat -> listOfNotNull(normalizeBodyFat(code, point))

            GoogleHealthDataType.HeartRateVariability -> listOfNotNull(normalizeHeartRateVariability(code, point))

            GoogleHealthDataType.RespiratoryRateSleepSummary -> listOfNotNull(normalizeRespiratoryRate(code, point))

            GoogleHealthDataType.DailyRestingHeartRate,
            GoogleHealthDataType.DailyHeartRateVariability,
            GoogleHealthDataType.DailyOxygenSaturation,
            GoogleHealthDataType.DailyRespiratoryRate,
            GoogleHealthDataType.DailySleepTemperature,
            -> normalizeDailyMetrics(dataType, point)

            GoogleHealthDataType.Activity -> listOfNotNull(normalizeActivity(code, point))
        }
    }

    private fun normalizeSteps(
        dataType: String,
        point: JsonObject,
    ): StepInterval? {
        val steps = point.objOrNull("steps") ?: return null
        val interval = steps.objOrNull("interval") ?: return null
        val startAt = interval.stringOrNull("startTime") ?: return null
        val endAt = interval.stringOrNull("endTime") ?: return null
        val count = steps.longOrNull("count") ?: return null
        if (count <= 0) return null
        return StepInterval(
            providerRecordId =
                providerRecordId(
                    dataType,
                    point,
                    startAt,
                    endAt,
                ),
            startAt = startAt,
            endAt = endAt,
            steps = count.toInt(),
        )
    }

    private fun normalizeSleep(
        dataType: String,
        point: JsonObject,
        hasMainSleep: Boolean,
    ): List<IngestionRecord> {
        val sleep = point.objOrNull("sleep") ?: return emptyList()
        val interval = sleep.objOrNull("interval") ?: return emptyList()
        val startAt = interval.stringOrNull("startTime") ?: return emptyList()
        val endAt = interval.stringOrNull("endTime") ?: return emptyList()
        val stages =
            sleep["stages"]
                ?.jsonArray
                ?.mapNotNull { element ->
                    val stage = element as? JsonObject ?: return@mapNotNull null
                    val mapped =
                        mapSleepStage(stage.stringOrNull("type")) ?: return@mapNotNull null
                    val stageStart = stage.stringOrNull("startTime") ?: return@mapNotNull null
                    val stageEnd = stage.stringOrNull("endTime") ?: return@mapNotNull null
                    SleepStage(
                        stage = mapped,
                        startAt = stageStart,
                        endAt = stageEnd,
                    )
                }.orEmpty()

        val session =
            SleepSession(
                providerRecordId =
                    providerRecordId(
                        dataType,
                        point,
                        startAt,
                        endAt,
                    ),
                startAt = startAt,
                endAt = endAt,
                stages = stages,
            )
        val isNightlySleep = if (hasMainSleep) sleep.sleepMetadataFlag("mainSleep") == true else sleep.sleepMetadataFlag("nap") != true
        return listOfNotNull(session, sleepSummary(session, sleep).takeIf { isNightlySleep })
    }

    private fun JsonObject.sleepMetadataFlag(key: String): Boolean? =
        objOrNull("metadata")
            ?.get(key)
            ?.primitiveOrNull()
            ?.booleanOrNull

    private fun sleepSummary(
        session: SleepSession,
        sleep: JsonObject,
    ): SleepSummary? {
        val summary = sleep.objOrNull("summary") ?: return null
        val stageSummaries =
            summary["stagesSummary"]
                ?.jsonArray
                ?.filterIsInstance<JsonObject>()
                ?.associateBy { it.stringOrNull("type") }
                .orEmpty()
        val minutesAsleep = summary.nonNegativeLong("minutesAsleep")
        val minutesInSleepPeriod = summary.nonNegativeLong("minutesInSleepPeriod")
        val record =
            SleepSummary(
                providerRecordId = "${session.providerRecordId}:summary",
                startAt = session.startAt,
                endAt = session.endAt,
                timeInBedSeconds = minutesInSleepPeriod?.times(60),
                totalSleepSeconds = minutesAsleep?.times(60),
                lightSleepSeconds = stageSummaries["LIGHT"]?.nonNegativeLong("minutes")?.times(60),
                deepSleepSeconds = stageSummaries["DEEP"]?.nonNegativeLong("minutes")?.times(60),
                remSleepSeconds = stageSummaries["REM"]?.nonNegativeLong("minutes")?.times(60),
                sleepEfficiencyPercent =
                    if (minutesAsleep != null && minutesInSleepPeriod != null && minutesInSleepPeriod > 0) {
                        (minutesAsleep * 100.0 / minutesInSleepPeriod).takeIf { it <= 100.0 }
                    } else {
                        null
                    },
                sleepLatencySeconds = summary.nonNegativeLong("minutesToFallAsleep")?.times(60),
                wakeupLatencySeconds = summary.nonNegativeLong("minutesAfterWakeUp")?.times(60),
                wakeupDurationSeconds = summary.nonNegativeLong("minutesAwake")?.times(60),
                wakeupCount = stageSummaries["AWAKE"]?.nonNegativeLong("count")?.toInt(),
                remEpisodesCount = stageSummaries["REM"]?.nonNegativeLong("count")?.toInt(),
            )
        return record.takeIf { it.hasAnyMetric() }
    }

    private fun normalizeHeartRateVariability(
        dataType: String,
        point: JsonObject,
    ): ScalarSample? {
        val hrv = point.objOrNull("heartRateVariability") ?: return null
        val measuredAt = hrv.objOrNull("sampleTime")?.stringOrNull("physicalTime") ?: return null
        val rmssd = hrv.finiteDoubleOrNull("rootMeanSquareOfSuccessiveDifferencesMilliseconds") ?: return null
        if (rmssd <= 0.0 || rmssd > 500.0) return null
        return ScalarSample(
            providerRecordId = providerRecordId(dataType, point, measuredAt, null),
            measuredAt = measuredAt,
            metricType = ScalarMetricTypes.HRV_RMSSD,
            value = rmssd,
            context = "unknown",
        )
    }

    private fun normalizeRespiratoryRate(
        dataType: String,
        point: JsonObject,
    ): ScalarSample? {
        val summary = point.objOrNull("respiratoryRateSleepSummary") ?: return null
        val measuredAt = summary.objOrNull("sampleTime")?.stringOrNull("physicalTime") ?: return null
        val breathsPerMinute =
            summary.objOrNull("fullSleepStats")?.finiteDoubleOrNull("breathsPerMinute") ?: return null
        if (breathsPerMinute !in 5.0..80.0) return null
        return ScalarSample(
            providerRecordId = providerRecordId(dataType, point, measuredAt, null),
            measuredAt = measuredAt,
            metricType = ScalarMetricTypes.RESPIRATORY_RATE,
            value = breathsPerMinute,
            context = "sleep",
        )
    }

    private fun normalizeHeartRate(
        dataType: String,
        point: JsonObject,
    ): ScalarSample? {
        val heartRate = point.objOrNull("heartRate") ?: return null
        val sampleTime = heartRate.objOrNull("sampleTime") ?: return null
        val measuredAt = sampleTime.stringOrNull("physicalTime") ?: return null
        val bpm = heartRate.longOrNull("beatsPerMinute") ?: return null
        if (bpm !in 25..250) return null
        return ScalarSample(
            providerRecordId =
                providerRecordId(
                    dataType,
                    point,
                    measuredAt,
                    null,
                ),
            measuredAt = measuredAt,
            metricType = ScalarMetricTypes.HEART_RATE,
            value = bpm.toDouble(),
            context =
                mapHeartRateContext(
                    heartRate.objOrNull("metadata")?.stringOrNull("motionContext"),
                ),
        )
    }

    private fun normalizeWeight(
        dataType: String,
        point: JsonObject,
    ): ScalarSample? {
        val weight = point.objOrNull("weight") ?: return null
        val sampleTime = weight.objOrNull("sampleTime") ?: return null
        val measuredAt = sampleTime.stringOrNull("physicalTime") ?: return null
        val grams = weight.finiteDoubleOrNull("weightGrams") ?: return null
        if (grams <= 0.0) return null
        return ScalarSample(
            providerRecordId =
                providerRecordId(
                    dataType,
                    point,
                    measuredAt,
                    null,
                ),
            measuredAt = measuredAt,
            metricType = BodyMetricTypes.WEIGHT,
            value = grams / 1000.0,
        )
    }

    private fun normalizeBodyFat(
        dataType: String,
        point: JsonObject,
    ): ScalarSample? {
        val bodyFat = point.objOrNull("bodyFat") ?: return null
        val sampleTime = bodyFat.objOrNull("sampleTime") ?: return null
        val measuredAt = sampleTime.stringOrNull("physicalTime") ?: return null
        val percentage = bodyFat.finiteDoubleOrNull("percentage") ?: return null
        if (percentage <= 0.0 || percentage > 100.0) return null
        return ScalarSample(
            providerRecordId =
                providerRecordId(
                    dataType,
                    point,
                    measuredAt,
                    null,
                ),
            measuredAt = measuredAt,
            metricType = BodyMetricTypes.BODY_FAT,
            value = percentage,
        )
    }

    /** Leaves out the payload, so a field Google adds later cannot turn the same reading into a new record. */
    private fun providerRecordId(
        dataType: String,
        point: JsonObject,
        startOrMeasuredAt: String,
        endAt: String?,
    ): String =
        point.stringOrNull("dataPointName")?.takeIf { it.isNotBlank() }
            ?: "$dataType:$startOrMeasuredAt:${endAt ?: "none"}"

    private fun normalizeDailyMetrics(
        dataType: GoogleHealthDataType,
        point: JsonObject,
    ): List<DailyMetric> {
        val (field, values) =
            when (dataType) {
                GoogleHealthDataType.DailyRestingHeartRate -> {
                    "dailyRestingHeartRate" to listOf(DailyMetricTypes.RESTING_HEART_RATE to "beatsPerMinute")
                }

                GoogleHealthDataType.DailyHeartRateVariability -> {
                    "dailyHeartRateVariability" to
                        listOf(
                            DailyMetricTypes.NIGHTLY_HRV_RMSSD to "averageHeartRateVariabilityMilliseconds",
                            DailyMetricTypes.DEEP_SLEEP_HRV_RMSSD to "deepSleepRootMeanSquareOfSuccessiveDifferencesMilliseconds",
                            DailyMetricTypes.NON_REM_HEART_RATE to "nonRemHeartRateBeatsPerMinute",
                        )
                }

                GoogleHealthDataType.DailyOxygenSaturation -> {
                    "dailyOxygenSaturation" to
                        listOf(
                            DailyMetricTypes.OXYGEN_SATURATION to "averagePercentage",
                            DailyMetricTypes.OXYGEN_SATURATION_LOWER_BOUND to "lowerBoundPercentage",
                        )
                }

                GoogleHealthDataType.DailyRespiratoryRate -> {
                    "dailyRespiratoryRate" to listOf(DailyMetricTypes.NIGHTLY_RESPIRATORY_RATE to "breathsPerMinute")
                }

                GoogleHealthDataType.DailySleepTemperature -> {
                    "dailySleepTemperatureDerivations" to
                        listOf(
                            DailyMetricTypes.NIGHTLY_SKIN_TEMPERATURE to "nightlyTemperatureCelsius",
                            DailyMetricTypes.SKIN_TEMPERATURE_BASELINE to "baselineTemperatureCelsius",
                        )
                }

                else -> {
                    error("${dataType.code} is not a daily metric type")
                }
            }
        val body = point.objOrNull(field) ?: return emptyList()
        val date = body.objOrNull("date")?.civilDate() ?: return emptyList()
        return values.mapNotNull { (metricType, key) ->
            val value = body.finiteDoubleOrNull(key)?.roundToScalarPrecision() ?: return@mapNotNull null
            if (value !in DailyMetricRegistry.find(metricType)!!.valueRange) return@mapNotNull null
            DailyMetric(
                providerRecordId = "${dataType.code}:$date:$metricType",
                date = date.toString(),
                metricType = metricType,
                value = value,
            )
        }
    }

    /** Google leaves a roll-up value unset for a day nothing was worn, so absent fields stay null instead of becoming zeros. */
    private fun normalizeActivity(
        dataType: String,
        point: JsonObject,
    ): ActivitySummary? {
        val date = point.stringOrNull("date") ?: return null
        // A worn day lists only the activity levels that had minutes, so a missing level is zero.
        val minutesByLevel =
            point
                .objOrNull("activeMinutes")
                ?.let { activeMinutes ->
                    val listed =
                        activeMinutes["activeMinutesRollupByActivityLevel"]
                            ?.jsonArray
                            ?.filterIsInstance<JsonObject>()
                            ?.mapNotNull { level -> level.stringOrNull("activityLevel")?.let { it to level.nonNegativeLong("activeMinutesSum")?.toInt() } }
                            ?.toMap()
                            .orEmpty()
                    listOf("LIGHT", "MODERATE", "VIGOROUS").associateWith { listed[it] ?: 0 }
                }.orEmpty()
        val moderate = minutesByLevel["MODERATE"]
        val vigorous = minutesByLevel["VIGOROUS"]
        val heartRate = point.objOrNull("heartRate")
        val summary =
            ActivitySummary(
                providerRecordId = "$dataType:$date",
                date = date,
                distanceMeters = point.objOrNull("distance")?.nonNegativeLong("millimetersSum")?.let { (it / 1000.0).roundToScalarPrecision() },
                totalEnergyKcal =
                    point
                        .objOrNull("totalCalories")
                        ?.finiteDoubleOrNull("kcalSum")
                        ?.takeIf { it >= 0.0 }
                        ?.roundToScalarPrecision(),
                softMinutes = minutesByLevel["LIGHT"],
                moderateMinutes = moderate,
                intenseMinutes = vigorous,
                activeMinutes = if (moderate != null && vigorous != null) moderate + vigorous else null,
                averageHeartRateBpm = heartRate?.heartRateBpm("beatsPerMinuteAvg"),
                minHeartRateBpm = heartRate?.heartRateBpm("beatsPerMinuteMin"),
                maxHeartRateBpm = heartRate?.heartRateBpm("beatsPerMinuteMax"),
            )
        return summary.takeIf { it.hasAnyMetric() }
    }

    private fun JsonObject.heartRateBpm(key: String): Int? = finiteDoubleOrNull(key)?.roundToInt()?.takeIf { it in 25..250 }

    // ProtoJSON writes non-finite doubles as "NaN" and "Infinity", which parse as numbers.
    private fun JsonObject.finiteDoubleOrNull(key: String): Double? = doubleOrNull(key)?.takeIf { it.isFinite() }

    private fun JsonObject.civilDate(): LocalDate? {
        val year = longOrNull("year")?.toInt() ?: return null
        val month = longOrNull("month")?.toInt() ?: return null
        val day = longOrNull("day")?.toInt() ?: return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    private fun mapSleepStage(value: String?): String? =
        when (value) {
            "AWAKE" -> "awake"
            "RESTLESS" -> "restless"
            "ASLEEP" -> "asleep"
            "LIGHT" -> "light"
            "DEEP" -> "deep"
            "REM" -> "rem"
            else -> null
        }

    private fun mapHeartRateContext(value: String?): String =
        when (value) {
            "ACTIVE" -> "active"
            "SEDENTARY" -> "resting"
            else -> "unknown"
        }

    private fun JsonObject.nonNegativeLong(key: String): Long? = longOrNull(key)?.takeIf { it >= 0 }
}
