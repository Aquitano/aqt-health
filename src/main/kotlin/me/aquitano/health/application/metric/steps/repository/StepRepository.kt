package me.aquitano.health.application.metric.steps.repository

import me.aquitano.health.application.metric.common.repository.BaseMetricReadRepository
import me.aquitano.health.application.metric.common.repository.DailyReadFilters
import me.aquitano.health.infrastructure.database.tables.StepDailySummariesTable
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.select

/** Raw step_daily_summaries aggregation for trend comparisons; list reads go through the canonical repository. */
class StepRepository : BaseMetricReadRepository() {
    fun sumStepDailySummaries(filters: DailyReadFilters): DashboardStepsSummaryRow {
        val where = dateConditions(
            filters = filters,
            sourceInstanceIdColumn = StepDailySummariesTable.sourceInstanceId,
            dateColumn = StepDailySummariesTable.date,
        ).whereOrNull() ?: return DashboardStepsSummaryRow(
            steps = 0,
            sampleCount = 0,
        )
        val stepsExpression = StepDailySummariesTable.steps.sum()
        val sampleCountExpression = StepDailySummariesTable.sampleCount.sum()
        val row = StepDailySummariesTable
            .select(stepsExpression, sampleCountExpression)
            .where(where)
            .single()
        return DashboardStepsSummaryRow(
            steps = row[stepsExpression] ?: 0,
            sampleCount = row[sampleCountExpression] ?: 0,
        )
    }
}
