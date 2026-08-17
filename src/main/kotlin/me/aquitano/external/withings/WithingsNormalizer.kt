package me.aquitano.external.withings

import me.aquitano.health.application.providersync.NormalizedProviderBatch
import me.aquitano.health.shared.doubleOrNull
import me.aquitano.health.shared.longOrNull
import me.aquitano.health.shared.primitiveOrNull
import me.aquitano.health.shared.stringOrNull
import kotlinx.serialization.json.*
import me.aquitano.health.api.dto.*
import me.aquitano.health.domain.BodyMetricTypes
import me.aquitano.health.domain.BodySegments
import me.aquitano.health.domain.CardiovascularMetricTypes
import me.aquitano.health.domain.ScalarMetricTypes
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.pow

class WithingsNormalizer {
    private val sleepSessionGap = Duration.ofHours(2)

    fun normalize(fetchResult: WithingsFetchResult): NormalizedProviderBatch {
        val records = when (fetchResult.dataType) {
            "activity" -> normalizeActivity(fetchResult.records)
            "measures" -> normalizeMeasures(fetchResult.records)
            "sleep-summary" -> normalizeSleepSummary(fetchResult.records)
            "sleep" -> normalizeSleep(fetchResult.records)
            else -> emptyList()
        }
        val sourcePayload = buildJsonObject {
            put("dataType", fetchResult.dataType)
            put(
                "pages",
                JsonArray(
                    fetchResult.pages.map {
                        buildJsonObject {
                            put("endpoint", it.endpoint)
                            put("action", it.action)
                            put("pageIndex", it.pageIndex)
                            put("payload", it.payload)
                        }
                    }
                )
            )
            put("records", JsonArray(fetchResult.records))
        }
        return NormalizedProviderBatch(sourcePayload, records)
    }

    private fun normalizeActivity(records: List<JsonObject>): List<IngestionRecord> =
        buildList {
            records.forEach { record ->
                val date = record.stringOrNull("date")
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?: return@forEach
                val steps = record.int("steps")
                if (steps != null && steps > 0) {
                    add(
                        StepInterval(
                            providerRecordId = "withings:activity:$date",
                            startAt = date.atStartOfDay()
                                .toInstant(ZoneOffset.UTC)
                                .toString(),
                            endAt = date.plusDays(1)
                                .atStartOfDay()
                                .toInstant(ZoneOffset.UTC)
                                .toString(),
                            steps = steps,
                        )
                    )
                }

                val summary = record.toActivitySummary(date)
                if (summary.hasAnyMetric()) add(summary)
            }
        }

    private fun normalizeMeasures(records: List<JsonObject>): List<IngestionRecord> =
        buildList {
            records.forEach { group ->
                val measuredAt = group.longOrNull("date") ?: group.longOrNull("created")
                ?: return@forEach
                val measuredAtString =
                    Instant.ofEpochSecond(measuredAt).toString()
                val grpid =
                    group.stringOrNull("grpid") ?: group.longOrNull("grpid")?.toString()
                    ?: "at-$measuredAt"
                val measures = group["measures"] as? JsonArray ?: return@forEach

                val samples = mutableListOf<ScalarSample>()
                var systolicMmhg: Int? = null
                var diastolicMmhg: Int? = null
                var heartRateBpm: Int? = null

                fun scalar(metricType: String, value: Double, segment: String? = null) {
                    samples.add(
                        ScalarSample(
                            providerRecordId = providerId(grpid, metricType, segment),
                            measuredAt = measuredAtString,
                            metricType = metricType,
                            value = value,
                            segment = segment,
                        )
                    )
                }

                fun segmental(metricType: String, measure: JsonObject, value: Double) {
                    val segment = measure.stringOrNull("zone")
                        ?.takeIf { it in BodySegments.supported }
                        ?: return
                    scalar(metricType, value, segment)
                }

                measures.mapNotNull { it as? JsonObject }.forEach { measure ->
                    val type = measure.int("type") ?: return@forEach
                    val value = measure.doubleOrNull("value") ?: return@forEach
                    val unit = measure.int("unit") ?: 0
                    val realValue = value * 10.0.pow(unit)
                    when (type) {
                        1 -> if (realValue > 0.0) scalar(BodyMetricTypes.WEIGHT, realValue)
                        5 -> if (realValue > 0.0) scalar(BodyMetricTypes.FAT_FREE_MASS, realValue)
                        6 -> if (realValue in 0.0..100.0) scalar(BodyMetricTypes.BODY_FAT, realValue)
                        8 -> if (realValue > 0.0) scalar(BodyMetricTypes.FAT_MASS, realValue)
                        9 -> if (realValue.toInt() in 30..200) diastolicMmhg = realValue.toInt()
                        10 -> if (realValue.toInt() in 60..300) systolicMmhg = realValue.toInt()
                        11 -> if (realValue.toInt() in 25..250) heartRateBpm = realValue.toInt()
                        76 -> if (realValue > 0.0) scalar(BodyMetricTypes.MUSCLE, realValue)
                        77 -> if (realValue in 0.0..100.0) scalar(BodyMetricTypes.WATER, realValue)
                        88 -> if (realValue > 0.0) scalar(BodyMetricTypes.BONE_MASS, realValue)
                        91 -> if (realValue > 0.0) {
                            scalar(CardiovascularMetricTypes.PULSE_WAVE_VELOCITY, realValue)
                        }
                        130 -> if (realValue >= 0.0) scalar(BodyMetricTypes.EXTRACELLULAR_WATER, realValue)
                        135 -> if (realValue >= 0.0) scalar(BodyMetricTypes.INTRACELLULAR_WATER, realValue)
                        136 -> if (realValue > 0.0) {
                            segmental(BodyMetricTypes.SEGMENTAL_FAT_MASS, measure, realValue)
                        }
                        137 -> if (realValue > 0.0) {
                            segmental(BodyMetricTypes.SEGMENTAL_MUSCLE_MASS, measure, realValue)
                        }
                        138 -> if (realValue > 0.0) {
                            segmental(BodyMetricTypes.SEGMENTAL_FAT_FREE_MASS, measure, realValue)
                        }
                        139 -> if (realValue > 0.0) {
                            scalar(CardiovascularMetricTypes.VASCULAR_AGE, realValue)
                        }
                        155 -> if (realValue.toInt() in 25..250) {
                            scalar(CardiovascularMetricTypes.STANDING_HEART_RATE, realValue)
                        }
                        170 -> if (realValue > 0.0) scalar(BodyMetricTypes.VISCERAL_FAT, realValue)
                        173 -> if (realValue > 0.0) scalar(BodyMetricTypes.BASAL_METABOLIC_RATE, realValue)
                    }
                }

                // A measure group's heart rate belongs to its blood pressure reading when the
                // group carries one; only a group without blood pressure emits it standalone.
                val hasBloodPressure = systolicMmhg != null &&
                    diastolicMmhg != null &&
                    systolicMmhg > diastolicMmhg
                if (hasBloodPressure) {
                    add(
                        BloodPressure(
                            providerRecordId = "withings:measure:$grpid:bp",
                            measuredAt = measuredAtString,
                            systolicMmhg = systolicMmhg,
                            diastolicMmhg = diastolicMmhg,
                            heartRateBpm = heartRateBpm,
                        )
                    )
                } else {
                    heartRateBpm?.let {
                        samples.add(
                            ScalarSample(
                                providerRecordId = providerId(grpid, ScalarMetricTypes.HEART_RATE),
                                measuredAt = measuredAtString,
                                metricType = ScalarMetricTypes.HEART_RATE,
                                value = it.toDouble(),
                                context = "general",
                            )
                        )
                    }
                }

                addAll(samples)
            }
        }

    private fun providerId(grpid: String, metricType: String, segment: String? = null): String =
        "withings:measure:$grpid:$metricType" + (segment?.let { ":$it" } ?: "")

    private fun normalizeSleepSummary(records: List<JsonObject>): List<IngestionRecord> =
        buildList {
            records.forEach { record ->
                val start = record.longOrNull("startdate") ?: return@forEach
                val end = record.longOrNull("enddate") ?: return@forEach
                if (start >= end) return@forEach
                // No SleepSession here: the `sleep` data type emits the stage-derived session for
                // the same night, and emitting one from the summary duplicates every night.
                val data = record["data"] as? JsonObject ?: record
                val totalSleepSeconds =
                    data.nonNegativeLong("total_sleep_time")
                        ?: data.nonNegativeLong("asleepduration")
                val sleepLatencySeconds =
                    data.nonNegativeLong("sleep_latency")
                        ?: data.nonNegativeLong("durationtosleep")
                val wakeupLatencySeconds =
                    data.nonNegativeLong("wakeup_latency")
                        ?: data.nonNegativeLong("durationtowakeup")
                val sleepScore = data.int("sleep_score")?.takeIf { it in 0..100 }
                val summary = SleepSummary(
                    providerRecordId = "withings:sleep-summary:$start:$end:summary",
                    startAt = Instant.ofEpochSecond(start).toString(),
                    endAt = Instant.ofEpochSecond(end).toString(),
                    timeInBedSeconds = data.nonNegativeLong("total_timeinbed"),
                    totalSleepSeconds = totalSleepSeconds,
                    lightSleepSeconds = data.nonNegativeLong("lightsleepduration"),
                    deepSleepSeconds = data.nonNegativeLong("deepsleepduration"),
                    remSleepSeconds = data.nonNegativeLong("remsleepduration"),
                    sleepEfficiencyPercent = data.nonNegativeDouble("sleep_efficiency")
                        ?.takeIf { it in 0.0..100.0 },
                    sleepLatencySeconds = sleepLatencySeconds,
                    wakeupLatencySeconds = wakeupLatencySeconds,
                    wakeupDurationSeconds = data.nonNegativeLong("wakeupduration"),
                    wakeupCount = data.nonNegativeInt("wakeupcount"),
                    wasoSeconds = data.nonNegativeLong("waso"),
                    sleepScore = sleepScore,
                    remEpisodesCount = data.nonNegativeInt("nb_rem_episodes"),
                    outOfBedCount = data.nonNegativeInt("out_of_bed_count"),
                    awakeDurationSeconds = data.nonNegativeLong("awake_duration"),
                    overnightHrvRmssd = data.doubleOrNull("rmssd_start_avg"),
                    respiratoryRhythm = data.doubleOrNull("chest_movement_rate_wellness_average"),
                    breathingQuality = data.int("breathing_quality_assessment")
                        ?.takeIf { it in 0..100 },
                    snoringDurationSeconds = data.nonNegativeLong("snoring"),
                    apneaHypopneaIndex = data.doubleOrNull("apnea_hypopnea_index")
                        ?.takeIf { it >= 0.0 },
                    movementScore = data.doubleOrNull("mvt_score_avg"),
                    snoringEpisodeCount = data.nonNegativeInt("snoringepisodecount"),
                    hrAverageBpm = data.validHeartRate("hr_average"),
                    hrMinBpm = data.validHeartRate("hr_min"),
                    hrMaxBpm = data.validHeartRate("hr_max"),
                    rrAverage = data.doubleOrNull("rr_average")
                        ?.takeIf { it in 5.0..40.0 },
                    rrMin = data.doubleOrNull("rr_min")
                        ?.takeIf { it in 5.0..40.0 },
                    rrMax = data.doubleOrNull("rr_max")
                        ?.takeIf { it in 5.0..40.0 },
                )
                if (summary.hasAnyMetric()) add(summary)
            }
        }

    private fun normalizeSleep(records: List<JsonObject>): List<IngestionRecord> {
        val segments = records.mapNotNull { record ->
            val start =
                record.sleepInstant("startdate") ?: return@mapNotNull null
            val end = record.sleepInstant("enddate") ?: return@mapNotNull null
            val stage =
                mapSleepStage(record.sleepState()) ?: return@mapNotNull null
            if (!start.isBefore(end)) return@mapNotNull null
            SleepSegment(start = start, end = end, stage = stage)
        }.sortedBy { it.start }

        val heartRates = records.mapNotNull { record ->
            val bpm = record.sleepHeartRate() ?: return@mapNotNull null
            if (bpm !in 25..250) return@mapNotNull null
            val instant = record.sleepInstant("timestamp")
                ?: record.sleepInstant("startdate")
                ?: return@mapNotNull null
            ScalarSample(
                providerRecordId = "withings:sleep:hr:${instant.epochSecond}",
                measuredAt = instant.toString(),
                metricType = ScalarMetricTypes.HEART_RATE,
                value = bpm.toDouble(),
                context = "sleep",
            )
        }

        val respiratoryRates = records.mapNotNull { record ->
            val breathsPerMinute = record.sleepRespiratoryRate() ?: return@mapNotNull null
            if (breathsPerMinute !in 5..80) return@mapNotNull null
            val instant = record.sleepInstant("timestamp")
                ?: record.sleepInstant("startdate")
                ?: return@mapNotNull null
            ScalarSample(
                providerRecordId = "withings:sleep:rr:${instant.epochSecond}",
                measuredAt = instant.toString(),
                metricType = ScalarMetricTypes.RESPIRATORY_RATE,
                value = breathsPerMinute.toDouble(),
                context = "sleep",
            )
        }

        val hrv = records.mapNotNull { record ->
            val rmssd = record.sleepRmssd() ?: return@mapNotNull null
            if (rmssd <= 0.0 || rmssd > 500.0) return@mapNotNull null
            val instant = record.sleepInstant("timestamp")
                ?: record.sleepInstant("startdate")
                ?: return@mapNotNull null
            ScalarSample(
                providerRecordId = "withings:sleep:rmssd:${instant.epochSecond}",
                measuredAt = instant.toString(),
                metricType = ScalarMetricTypes.HRV_RMSSD,
                value = rmssd,
                context = "sleep",
            )
        }

        if (segments.isNotEmpty()) {
            val sessions =
                splitSleepSegments(segments).mapNotNull { sessionSegments ->
                    val start = sessionSegments.first().start
                    val end = sessionSegments.last().end
                    if (!start.isBefore(end)) return@mapNotNull null
                    SleepSession(
                        providerRecordId = "withings:sleep:${start.epochSecond}:${end.epochSecond}",
                        startAt = start.toString(),
                        endAt = end.toString(),
                        stages = sessionSegments.map { segment ->
                            SleepStage(
                                stage = segment.stage,
                                startAt = segment.start.toString(),
                                endAt = segment.end.toString(),
                            )
                        },
                    )
                }
            return sessions + heartRates + respiratoryRates + hrv
        }

        val sorted = records.mapNotNull { record ->
            val instant = record.sleepInstant("timestamp")
                ?: record.sleepInstant("startdate")
                ?: return@mapNotNull null
            instant to record
        }.sortedBy { it.first }
        val sessions = splitSleepSessions(sorted).mapNotNull { sessionRecords ->
            val stages =
                sessionRecords.zipWithNext().mapNotNull { (current, next) ->
                    val stage = mapSleepStage(current.second.sleepState())
                        ?: return@mapNotNull null
                    if (!current.first.isBefore(next.first)) return@mapNotNull null
                    SleepStage(
                        stage = stage,
                        startAt = current.first.toString(),
                        endAt = next.first.toString(),
                    )
                }
            if (stages.isEmpty()) return@mapNotNull null
            val start = sessionRecords.first().first
            val end = sessionRecords.last().first
            if (!start.isBefore(end)) return@mapNotNull null
            SleepSession(
                providerRecordId = "withings:sleep:${start.epochSecond}:${end.epochSecond}",
                startAt = start.toString(),
                endAt = end.toString(),
                stages = stages,
            )
        }
        return sessions + heartRates + respiratoryRates + hrv
    }

    private fun splitSleepSegments(segments: List<SleepSegment>): List<List<SleepSegment>> =
        buildList {
            var current = mutableListOf<SleepSegment>()
            segments.forEach { segment ->
                val previous = current.lastOrNull()
                if (previous != null && Duration.between(
                        previous.end,
                        segment.start
                    ) > sleepSessionGap
                ) {
                    add(current)
                    current = mutableListOf()
                }
                current.add(segment)
            }
            if (current.isNotEmpty()) add(current)
        }

    private data class SleepSegment(
        val start: Instant,
        val end: Instant,
        val stage: String,
    )

    private fun splitSleepSessions(
        sorted: List<Pair<Instant, JsonObject>>,
    ): List<List<Pair<Instant, JsonObject>>> =
        buildList {
            var current = mutableListOf<Pair<Instant, JsonObject>>()
            sorted.forEach { record ->
                val previous = current.lastOrNull()
                if (previous != null && Duration.between(
                        previous.first,
                        record.first
                    ) > sleepSessionGap
                ) {
                    add(current)
                    current = mutableListOf()
                }
                current.add(record)
            }
            if (current.isNotEmpty()) add(current)
        }

    private fun mapSleepStage(value: Int?): String? =
        when (value) {
            0 -> "awake"
            1 -> "light"
            2 -> "deep"
            3 -> "rem"
            null -> null
            else -> "unknown"
        }

    private fun JsonObject.toActivitySummary(date: LocalDate): ActivitySummary =
        ActivitySummary(
            providerRecordId = "withings:activity:$date:summary",
            date = date.toString(),
            distanceMeters = nonNegativeDouble("distance"),
            activeEnergyKcal = nonNegativeDouble("calories"),
            totalEnergyKcal = nonNegativeDouble("totalcalories"),
            elevationMeters = nonNegativeDouble("elevation"),
            softMinutes = nonNegativeInt("soft"),
            moderateMinutes = nonNegativeInt("moderate"),
            intenseMinutes = nonNegativeInt("intense"),
            activeMinutes = nonNegativeInt("active"),
            averageHeartRateBpm = validHeartRate("hr_average"),
            minHeartRateBpm = validHeartRate("hr_min"),
            maxHeartRateBpm = validHeartRate("hr_max"),
        )

    private fun ActivitySummary.hasAnyMetric(): Boolean =
        distanceMeters != null ||
            activeEnergyKcal != null ||
            totalEnergyKcal != null ||
            elevationMeters != null ||
            softMinutes != null ||
            moderateMinutes != null ||
            intenseMinutes != null ||
            activeMinutes != null ||
            averageHeartRateBpm != null ||
            minHeartRateBpm != null ||
            maxHeartRateBpm != null

    private fun SleepSummary.hasAnyMetric(): Boolean =
        timeInBedSeconds != null ||
            totalSleepSeconds != null ||
            lightSleepSeconds != null ||
            deepSleepSeconds != null ||
            remSleepSeconds != null ||
            sleepEfficiencyPercent != null ||
            sleepLatencySeconds != null ||
            wakeupLatencySeconds != null ||
            wakeupDurationSeconds != null ||
            wakeupCount != null ||
            wasoSeconds != null ||
            sleepScore != null ||
            remEpisodesCount != null ||
            outOfBedCount != null ||
            awakeDurationSeconds != null ||
            overnightHrvRmssd != null ||
            respiratoryRhythm != null ||
            breathingQuality != null ||
            snoringDurationSeconds != null ||
            apneaHypopneaIndex != null ||
            movementScore != null ||
            snoringEpisodeCount != null ||
            hrAverageBpm != null ||
            hrMinBpm != null ||
            hrMaxBpm != null ||
            rrAverage != null ||
            rrMin != null ||
            rrMax != null

    private fun JsonObject.int(key: String): Int? {
        val primitive = this[key]?.primitiveOrNull() ?: return null
        primitive.intOrNull?.let { return it }
        return primitive.contentOrNull?.toIntOrNull()
    }

    private fun JsonObject.instant(key: String): Instant? {
        val primitive = this[key]?.primitiveOrNull() ?: return null
        primitive.longOrNull?.let { return Instant.ofEpochSecond(it) }
        return primitive.contentOrNull?.let { value ->
            value.toLongOrNull()?.let { Instant.ofEpochSecond(it) }
                ?: runCatching { Instant.parse(value) }.getOrNull()
        }
    }

    private fun JsonObject.sleepState(): Int? =
        int("state")
            ?: int("value")
            ?: (this["data"] as? JsonObject)?.int("state")
            ?: (this["data"] as? JsonObject)?.int("value")
            ?: (this["value"] as? JsonObject)?.int("state")
            ?: (this["value"] as? JsonObject)?.int("value")

    private fun JsonObject.sleepHeartRate(): Int? =
        int("hr") ?: (this["data"] as? JsonObject)?.int("hr")

    private fun JsonObject.sleepRespiratoryRate(): Int? =
        int("rr") ?: (this["data"] as? JsonObject)?.int("rr")

    private fun JsonObject.sleepRmssd(): Double? =
        doubleOrNull("rmssd") ?: (this["data"] as? JsonObject)?.doubleOrNull("rmssd")

    private fun JsonObject.sleepInstant(key: String): Instant? =
        instant(key) ?: (this["data"] as? JsonObject)?.instant(key)

    private fun JsonObject.nonNegativeInt(key: String): Int? =
        int(key)?.takeIf { it >= 0 }

    private fun JsonObject.nonNegativeLong(key: String): Long? =
        longOrNull(key)?.takeIf { it >= 0 }

    private fun JsonObject.nonNegativeDouble(key: String): Double? =
        doubleOrNull(key)?.takeIf { it >= 0.0 }

    private fun JsonObject.validHeartRate(key: String): Int? =
        int(key)?.takeIf { it in 25..250 }
}
