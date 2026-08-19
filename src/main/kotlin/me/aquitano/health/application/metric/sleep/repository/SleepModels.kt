package me.aquitano.health.application.metric.sleep.repository

import me.aquitano.health.infrastructure.database.tables.SleepSessionsTable
import me.aquitano.health.infrastructure.database.tables.SleepSummariesTable
import org.jetbrains.exposed.v1.core.ResultRow
import java.time.Instant

data class SleepSessionRow(
    val id: Int,
    val sourceInstanceId: Int,
    val startAt: Instant,
    val endAt: Instant,
    val durationSeconds: Long
)

data class SleepNightRow(
    val id: Int,
    val date: String,
    val timezone: String,
    val session: SleepSessionRow,
)

data class SleepStageRow(
    val stage: String,
    val startAt: Instant,
    val endAt: Instant,
    val durationSeconds: Long
)

data class SleepSummaryRow(
    val id: Int,
    val sourceInstanceId: Int,
    val startAt: Instant,
    val endAt: Instant,
    val timeInBedSeconds: Long?,
    val totalSleepSeconds: Long?,
    val lightSleepSeconds: Long?,
    val deepSleepSeconds: Long?,
    val remSleepSeconds: Long?,
    val sleepEfficiencyPercent: Double?,
    val sleepLatencySeconds: Long?,
    val wakeupLatencySeconds: Long?,
    val wakeupDurationSeconds: Long?,
    val wakeupCount: Int?,
    val wasoSeconds: Long?,
    val sleepScore: Int?,
    val remEpisodesCount: Int?,
    val outOfBedCount: Int?,
    val awakeDurationSeconds: Long?,
    val overnightHrvRmssd: Double?,
    val respiratoryRhythm: Double?,
    val breathingQuality: Int?,
    val snoringDurationSeconds: Long?,
    val apneaHypopneaIndex: Double?,
    val movementScore: Double?,
    val snoringEpisodeCount: Int?,
    val hrAverageBpm: Int?,
    val hrMinBpm: Int?,
    val hrMaxBpm: Int?,
    val rrAverage: Double?,
    val rrMin: Double?,
    val rrMax: Double?,
)

internal fun toSleepSessionRow(row: ResultRow): SleepSessionRow =
    SleepSessionRow(
        id = row[SleepSessionsTable.id].value,
        sourceInstanceId = row[SleepSessionsTable.sourceInstanceId],
        startAt = row[SleepSessionsTable.startAt].toInstant(),
        endAt = row[SleepSessionsTable.endAt].toInstant(),
        durationSeconds = row[SleepSessionsTable.durationSeconds],
    )

internal fun toSleepSummaryRow(row: ResultRow): SleepSummaryRow =
    SleepSummaryRow(
        id = row[SleepSummariesTable.id].value,
        sourceInstanceId = row[SleepSummariesTable.sourceInstanceId],
        startAt = row[SleepSummariesTable.startAt].toInstant(),
        endAt = row[SleepSummariesTable.endAt].toInstant(),
        timeInBedSeconds = row[SleepSummariesTable.timeInBedSeconds],
        totalSleepSeconds = row[SleepSummariesTable.totalSleepSeconds],
        lightSleepSeconds = row[SleepSummariesTable.lightSleepSeconds],
        deepSleepSeconds = row[SleepSummariesTable.deepSleepSeconds],
        remSleepSeconds = row[SleepSummariesTable.remSleepSeconds],
        sleepEfficiencyPercent = row[SleepSummariesTable.sleepEfficiencyPercent],
        sleepLatencySeconds = row[SleepSummariesTable.sleepLatencySeconds],
        wakeupLatencySeconds = row[SleepSummariesTable.wakeupLatencySeconds],
        wakeupDurationSeconds = row[SleepSummariesTable.wakeupDurationSeconds],
        wakeupCount = row[SleepSummariesTable.wakeupCount],
        wasoSeconds = row[SleepSummariesTable.wasoSeconds],
        sleepScore = row[SleepSummariesTable.sleepScore],
        remEpisodesCount = row[SleepSummariesTable.remEpisodesCount],
        outOfBedCount = row[SleepSummariesTable.outOfBedCount],
        awakeDurationSeconds = row[SleepSummariesTable.awakeDurationSeconds],
        overnightHrvRmssd = row[SleepSummariesTable.overnightHrvRmssd],
        respiratoryRhythm = row[SleepSummariesTable.respiratoryRhythm],
        breathingQuality = row[SleepSummariesTable.breathingQuality],
        snoringDurationSeconds = row[SleepSummariesTable.snoringDurationSeconds],
        apneaHypopneaIndex = row[SleepSummariesTable.apneaHypopneaIndex],
        movementScore = row[SleepSummariesTable.movementScore],
        snoringEpisodeCount = row[SleepSummariesTable.snoringEpisodeCount],
        hrAverageBpm = row[SleepSummariesTable.hrAverageBpm],
        hrMinBpm = row[SleepSummariesTable.hrMinBpm],
        hrMaxBpm = row[SleepSummariesTable.hrMaxBpm],
        rrAverage = row[SleepSummariesTable.rrAverage],
        rrMin = row[SleepSummariesTable.rrMin],
        rrMax = row[SleepSummariesTable.rrMax],
    )
