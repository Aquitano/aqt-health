package me.aquitano.health.application.metric.steps.repository

import me.aquitano.health.infrastructure.database.tables.StepSamplesTable
import me.aquitano.health.infrastructure.database.toApiString
import org.jetbrains.exposed.v1.core.ResultRow

data class StepSampleRow(
    val id: Int,
    val sourceInstanceId: Int,
    val startAt: String,
    val endAt: String,
    val steps: Int
)

internal fun toStepSampleRow(row: ResultRow): StepSampleRow =
    StepSampleRow(
        id = row[StepSamplesTable.id].value,
        sourceInstanceId = row[StepSamplesTable.sourceInstanceId],
        startAt = row[StepSamplesTable.startAt].toApiString(),
        endAt = row[StepSamplesTable.endAt].toApiString(),
        steps = row[StepSamplesTable.steps],
    )

data class StepDailySummaryRow(
    val id: Int,
    val sourceInstanceId: Int,
    val date: String,
    val steps: Int,
    val sampleCount: Int
)

data class DashboardStepsSummaryRow(
    val steps: Int,
    val sampleCount: Int,
)

