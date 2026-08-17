package me.aquitano.external.google

import kotlinx.serialization.json.*
import me.aquitano.health.api.dto.*
import me.aquitano.health.application.providersync.NormalizedProviderBatch
import me.aquitano.health.shared.AppJson
import me.aquitano.health.shared.doubleOrNull
import me.aquitano.health.shared.longOrNull
import me.aquitano.health.shared.objOrNull
import me.aquitano.health.shared.stringOrNull
import java.security.MessageDigest
import java.util.*

class GoogleHealthNormalizer {
    fun normalize(fetchResult: GoogleHealthFetchResult): NormalizedProviderBatch {
        val records = fetchResult.dataPoints.mapNotNull {
            normalizeDataPoint(
                fetchResult.dataType,
                it
            )
        }
        val sourcePayload = buildJsonObject {
            put("dataType", fetchResult.dataType)
            put(
                "pages",
                JsonArray(
                    fetchResult.pages.map {
                        buildJsonObject {
                            put("pageIndex", it.pageIndex)
                            put("payload", it.payload)
                        }
                    }
                )
            )
        }
        return NormalizedProviderBatch(sourcePayload, records)
    }

    private fun normalizeDataPoint(
        dataType: String,
        dataPoint: JsonObject
    ): IngestionRecord? {
        val point = (dataPoint["dataPoint"] as? JsonObject) ?: dataPoint
        return when (dataType) {
            "steps" -> normalizeSteps(dataType, point)
            "sleep" -> normalizeSleep(dataType, point)
            "heart-rate" -> normalizeHeartRate(dataType, point)
            "weight" -> normalizeWeight(dataType, point)
            "body-fat" -> normalizeBodyFat(dataType, point)
            else -> null
        }
    }

    private fun normalizeSteps(
        dataType: String,
        point: JsonObject
    ): StepInterval? {
        val steps = point.objOrNull("steps") ?: return null
        val interval = steps.objOrNull("interval") ?: return null
        val startAt = interval.stringOrNull("startTime") ?: return null
        val endAt = interval.stringOrNull("endTime") ?: return null
        val count = steps.longOrNull("count") ?: return null
        if (count <= 0) return null
        return StepInterval(
            providerRecordId = providerRecordId(
                dataType,
                point,
                startAt,
                endAt
            ),
            startAt = startAt,
            endAt = endAt,
            steps = count.toInt()
        )
    }

    private fun normalizeSleep(
        dataType: String,
        point: JsonObject
    ): SleepSession? {
        val sleep = point.objOrNull("sleep") ?: return null
        val interval = sleep.objOrNull("interval") ?: return null
        val startAt = interval.stringOrNull("startTime") ?: return null
        val endAt = interval.stringOrNull("endTime") ?: return null
        val stages = sleep["stages"]?.jsonArray?.mapNotNull { element ->
            val stage = element as? JsonObject ?: return@mapNotNull null
            val mapped =
                mapSleepStage(stage.stringOrNull("type")) ?: return@mapNotNull null
            val stageStart = stage.stringOrNull("startTime") ?: return@mapNotNull null
            val stageEnd = stage.stringOrNull("endTime") ?: return@mapNotNull null
            SleepStage(
                stage = mapped,
                startAt = stageStart,
                endAt = stageEnd
            )
        }.orEmpty()

        return SleepSession(
            providerRecordId = providerRecordId(
                dataType,
                point,
                startAt,
                endAt
            ),
            startAt = startAt,
            endAt = endAt,
            stages = stages
        )
    }

    private fun normalizeHeartRate(
        dataType: String,
        point: JsonObject
    ): HeartRate? {
        val heartRate =
            point.objOrNull("heartRate") ?: point.objOrNull("heart_rate") ?: return null
        val sampleTime = heartRate.objOrNull("sampleTime") ?: return null
        val measuredAt = sampleTime.stringOrNull("physicalTime") ?: return null
        val bpm = heartRate.longOrNull("beatsPerMinute") ?: heartRate.longOrNull("bpm")
        ?: return null
        if (bpm !in 25..250) return null
        return HeartRate(
            providerRecordId = providerRecordId(
                dataType,
                point,
                measuredAt,
                null
            ),
            measuredAt = measuredAt,
            bpm = bpm.toInt(),
            context = mapHeartRateContext(
                heartRate.objOrNull("metadata")?.stringOrNull("motionContext")
            )
        )
    }

    private fun normalizeWeight(
        dataType: String,
        point: JsonObject
    ): BodyMeasurement? {
        val weight = point.objOrNull("weight") ?: return null
        val sampleTime = weight.objOrNull("sampleTime") ?: return null
        val measuredAt = sampleTime.stringOrNull("physicalTime") ?: return null
        val grams = weight.doubleOrNull("weightGrams") ?: return null
        if (grams <= 0.0) return null
        return BodyMeasurement(
            providerRecordId = providerRecordId(
                dataType,
                point,
                measuredAt,
                null
            ),
            measuredAt = measuredAt,
            weightKg = grams / 1000.0
        )
    }

    private fun normalizeBodyFat(
        dataType: String,
        point: JsonObject
    ): BodyMeasurement? {
        val bodyFat =
            point.objOrNull("bodyFat") ?: point.objOrNull("body_fat") ?: return null
        val sampleTime = bodyFat.objOrNull("sampleTime") ?: return null
        val measuredAt = sampleTime.stringOrNull("physicalTime") ?: return null
        val percentage = bodyFat.doubleOrNull("percentage") ?: return null
        if (percentage !in 0.0..100.0) return null
        return BodyMeasurement(
            providerRecordId = providerRecordId(
                dataType,
                point,
                measuredAt,
                null
            ),
            measuredAt = measuredAt,
            bodyFatPercent = percentage
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
        when (value?.uppercase()) {
            "AWAKE" -> "awake"
            "RESTLESS" -> "restless"
            "ASLEEP" -> "asleep"
            "LIGHT" -> "light"
            "DEEP" -> "deep"
            "REM" -> "rem"
            else -> null
        }

    private fun mapHeartRateContext(value: String?): String =
        when (value?.uppercase()) {
            "ACTIVE" -> "active"
            "SEDENTARY" -> "resting"
            else -> "unknown"
        }

    private fun JsonObject.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(AppJson.encodeToString(this).toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }
}
