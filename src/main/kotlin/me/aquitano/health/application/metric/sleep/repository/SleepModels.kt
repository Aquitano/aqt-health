package me.aquitano.health.application.metric.sleep.repository

import me.aquitano.health.infrastructure.database.tables.SleepSessionsTable
import org.jetbrains.exposed.v1.core.ResultRow
import java.time.Instant

data class SleepSessionRow(
    val id: Int,
    val sourceInstanceId: Int,
    val startAt: Instant,
    val endAt: Instant,
    val durationSeconds: Long,
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
    val durationSeconds: Long,
)

internal fun toSleepSessionRow(row: ResultRow): SleepSessionRow =
    SleepSessionRow(
        id = row[SleepSessionsTable.id].value,
        sourceInstanceId = row[SleepSessionsTable.sourceInstanceId],
        startAt = row[SleepSessionsTable.startAt].toInstant(),
        endAt = row[SleepSessionsTable.endAt].toInstant(),
        durationSeconds = row[SleepSessionsTable.durationSeconds],
    )
