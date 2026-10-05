package me.aquitano.external.google

import kotlinx.serialization.json.*
import me.aquitano.health.api.dto.*
import me.aquitano.health.domain.BodyMetricTypes
import me.aquitano.health.domain.ScalarMetricTypes
import me.aquitano.health.shared.AppJson
import me.aquitano.health.shared.doubleOrNull
import me.aquitano.health.shared.longOrNull
import me.aquitano.health.shared.objOrNull
import me.aquitano.health.shared.primitiveOrNull
import me.aquitano.health.shared.stringOrNull
import java.security.MessageDigest
import java.util.*

class GoogleHealthNormalizer {
    fun normalize(fetchResult: GoogleHealthFetchResult): List<IngestionRecord> = fetchResult.dataPoints.flatMap { normalizeDataPoint(fetchResult.dataType, it) }

    private fun normalizeDataPoint(
        dataType: GoogleHealthDataType,
        point: JsonObject,
    ): List<IngestionRecord> {
        val code = dataType.code
        return when (dataType) {
            GoogleHealthDataType.Steps -> listOfNotNull(normalizeSteps(code, point))
            GoogleHealthDataType.Sleep -> normalizeSleep(code, point)
            GoogleHealthDataType.HeartRate -> listOfNotNull(normalizeHeartRate(code, point))
            GoogleHealthDataType.Weight -> listOfNotNull(normalizeWeight(code, point))
            GoogleHealthDataType.BodyFat -> listOfNotNull(normalizeBodyFat(code, point))
            GoogleHealthDataType.HeartRateVariability -> listOfNotNull(normalizeHeartRateVariability(code, point))
            GoogleHealthDataType.RespiratoryRateSleepSummary -> listOfNotNull(normalizeRespiratoryRate(code, point))
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
        return listOfNotNull(session, sleepSummary(session, sleep))
    }

    private fun sleepSummary(
        session: SleepSession,
        sleep: JsonObject,
    ): SleepSummary? {
        // canonical_sleep_summaries keeps one summary per UTC start date, so a nap would
        // replace the night it shares a date with.
        val isNap =
            sleep
                .objOrNull("metadata")
                ?.get("nap")
                ?.primitiveOrNull()
                ?.booleanOrNull
        if (isNap == true) return null
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
        val withoutMetrics = SleepSummary(record.providerRecordId, record.startAt, record.endAt)
        return record.takeIf { it != withoutMetrics }
    }

    private fun normalizeHeartRateVariability(
        dataType: String,
        point: JsonObject,
    ): ScalarSample? {
        val hrv = point.objOrNull("heartRateVariability") ?: return null
        val measuredAt = hrv.objOrNull("sampleTime")?.stringOrNull("physicalTime") ?: return null
        val rmssd = hrv.doubleOrNull("rootMeanSquareOfSuccessiveDifferencesMilliseconds") ?: return null
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
            summary.objOrNull("fullSleepStats")?.doubleOrNull("breathsPerMinute") ?: return null
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
        val grams = weight.doubleOrNull("weightGrams") ?: return null
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
        val percentage = bodyFat.doubleOrNull("percentage") ?: return null
        if (percentage !in 0.0..100.0) return null
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

    private fun providerRecordId(
        dataType: String,
        point: JsonObject,
        startOrMeasuredAt: String,
        endAt: String?,
    ): String =
        point.stringOrNull("name")?.takeIf { it.isNotBlank() }
            ?: "$dataType:$startOrMeasuredAt:${endAt ?: "none"}:${point.sha256()}"

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

    private fun JsonObject.sha256(): String {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(AppJson.encodeToString(this).toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }
}
