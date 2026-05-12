package me.aquitano.health.infrastructure.providers.withings

import kotlinx.serialization.json.*
import me.aquitano.health.domain.RecordTypes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.pow

class WithingsNormalizer {
    fun normalize(fetchResult: WithingsFetchResult): WithingsNormalizedBatch {
        val records = fetchResult.pages.flatMap { page ->
            when (fetchResult.dataType) {
                WITHINGS_ACTIVITY_DATA_TYPE -> normalizeActivities(page)
                WITHINGS_BODY_MEASUREMENTS_DATA_TYPE -> normalizeMeasureGroups(page)
                WITHINGS_SLEEP_SUMMARY_DATA_TYPE -> normalizeSleepSummaries(page)
                else -> emptyList()
            }
        }
        val sourcePayload = buildJsonObject {
            put("dataType", fetchResult.dataType)
            put("pages", JsonArray(fetchResult.pages))
        }
        return WithingsNormalizedBatch(sourcePayload, records)
    }

    private fun normalizeActivities(page: JsonObject): List<JsonObject> =
        page.bodyArray("activities").mapNotNull { activity ->
            val date = activity.string("date") ?: return@mapNotNull null
            val steps = activity.long("steps") ?: return@mapNotNull null
            if (steps <= 0) return@mapNotNull null
            val startAt = date.toUtcStartOfDay() ?: return@mapNotNull null
            buildJsonObject {
                put("type", RecordTypes.STEP_INTERVAL)
                put("providerRecordId", "withings:activity:$date")
                put("startAt", startAt.toString())
                put("endAt", startAt.plusSeconds(86_400).toString())
                put("steps", steps)
            }
        }

    private fun normalizeMeasureGroups(page: JsonObject): List<JsonObject> =
        page.bodyArray("measuregrps").flatMap { group ->
            val measuredAt = group.long("date")?.let { Instant.ofEpochSecond(it).toString() } ?: return@flatMap emptyList()
            val groupId = group.long("grpid")?.toString() ?: measuredAt
            val measures = group.array("measures")
            val byType = measures.associateBy { it.int("type") }
            buildList {
                val weightKg = byType[1]?.realValue()
                val bodyFatPercent = byType[6]?.realValue()
                if (weightKg != null || bodyFatPercent != null) {
                    add(
                        buildJsonObject {
                            put("type", RecordTypes.BODY_MEASUREMENT)
                            put("providerRecordId", "withings:measure:$groupId:body")
                            put("measuredAt", measuredAt)
                            weightKg?.let { put("weightKg", it) }
                            bodyFatPercent?.let { put("bodyFatPercent", it) }
                        }
                    )
                }
                val bpm = byType[11]?.realValue()?.toInt()
                if (bpm != null && bpm in 25..250) {
                    add(
                        buildJsonObject {
                            put("type", RecordTypes.HEART_RATE)
                            put("providerRecordId", "withings:measure:$groupId:heart-rate")
                            put("measuredAt", measuredAt)
                            put("bpm", bpm)
                            put("context", "general")
                        }
                    )
                }
            }
        }

    private fun normalizeSleepSummaries(page: JsonObject): List<JsonObject> =
        page.bodyArray("series").mapNotNull { summary ->
            val startAt = summary.long("startdate")?.let(Instant::ofEpochSecond) ?: return@mapNotNull null
            val endAt = summary.long("enddate")?.let(Instant::ofEpochSecond) ?: return@mapNotNull null
            val data = summary["data"] as? JsonObject
            val stages = sleepStages(startAt, data)
            buildJsonObject {
                put("type", RecordTypes.SLEEP_SESSION)
                put("providerRecordId", "withings:sleep:${summary.string("id") ?: startAt.toString()}")
                put("startAt", startAt.toString())
                put("endAt", endAt.toString())
                put("stages", JsonArray(stages))
            }
        }

    private fun sleepStages(startAt: Instant, data: JsonObject?): List<JsonObject> {
        if (data == null) return emptyList()
        var cursor = startAt
        return buildList {
            appendStage(data.long("lightsleepduration"), "light", cursor)?.also { cursor = it }
            appendStage(data.long("deepsleepduration"), "deep", cursor)?.also { cursor = it }
            appendStage(data.long("remsleepduration"), "rem", cursor)?.also { cursor = it }
            appendStage(data.long("wakeupduration"), "awake", cursor)
        }
    }

    private fun MutableList<JsonObject>.appendStage(seconds: Long?, stage: String, startAt: Instant): Instant? {
        if (seconds == null || seconds <= 0) return null
        val endAt = startAt.plusSeconds(seconds)
        add(
            buildJsonObject {
                put("stage", stage)
                put("startAt", startAt.toString())
                put("endAt", endAt.toString())
            }
        )
        return endAt
    }

    private fun JsonObject.bodyArray(key: String): List<JsonObject> =
        (this["body"] as? JsonObject)?.array(key).orEmpty()

    private fun JsonObject.array(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.realValue(): Double? {
        val value = long("value") ?: return null
        val unit = int("unit") ?: return null
        return value * 10.0.pow(unit)
    }

    private fun String.toUtcStartOfDay(): Instant? =
        runCatching { LocalDate.parse(this).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()
}
