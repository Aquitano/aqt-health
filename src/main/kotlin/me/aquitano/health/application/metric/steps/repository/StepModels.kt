package me.aquitano.health.application.metric.steps.repository

import me.aquitano.health.infrastructure.database.tables.StepSamplesTable
import me.aquitano.health.infrastructure.database.tables.IngestionRecordsTable
import org.jetbrains.exposed.v1.core.ResultRow
import java.time.Instant

data class StepSampleRow(
    val id: Int,
    val sourceInstanceId: Int,
    val startAt: Instant,
    val endAt: Instant,
    val steps: Int,
    val allocationPriority: Int? = null,
)

internal fun toStepSampleRow(row: ResultRow): StepSampleRow =
    StepSampleRow(
        id = row[StepSamplesTable.id].value,
        sourceInstanceId = row[StepSamplesTable.sourceInstanceId],
        startAt = row[StepSamplesTable.startAt].toInstant(),
        endAt = row[StepSamplesTable.endAt].toInstant(),
        steps = row[StepSamplesTable.steps],
        allocationPriority = row.getOrNull(IngestionRecordsTable.googleStepAllocationPriority)
            ?: row[StepSamplesTable.ingestionRecordId],
    )

data class StepDailySummaryRow(
    val id: Int,
    val sourceInstanceId: Int?,
    val date: String,
    val steps: Int,
    val sampleCount: Int
)

data class DashboardStepsSummaryRow(
    val steps: Int,
    val dayCount: Int,
)
