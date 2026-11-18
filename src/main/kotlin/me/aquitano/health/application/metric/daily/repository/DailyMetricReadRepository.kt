package me.aquitano.health.application.metric.daily.repository

import me.aquitano.health.api.dto.DailyMetricResponse
import me.aquitano.health.application.metric.common.keysetFetchLimit
import me.aquitano.health.application.metric.common.repository.BaseMetricReadRepository
import me.aquitano.health.application.metric.common.repository.ReadFilters
import me.aquitano.health.domain.DailyMetricRegistry
import me.aquitano.health.infrastructure.database.tables.CanonicalDailyMetricValuesView
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll

class DailyMetricReadRepository : BaseMetricReadRepository() {
    fun listCanonical(
        filters: ReadFilters,
        metricTypes: Set<String>,
    ): List<DailyMetricResponse> {
        val view = CanonicalDailyMetricValuesView
        val where =
            dateConditions(filters, view.sourceInstanceId, view.date)
                ?.let { it and (view.metricType inList metricTypes) }
                ?: return emptyList()
        val keyset = dateKeyset(filters.cursor, filters.order, view.date, view.id)
        return view
            .selectAll()
            .where(keyset?.let { where and it } ?: where)
            .orderBy(view.date to filters.sortOrder(), view.id to filters.sortOrder())
            .limit(keysetFetchLimit(filters.limit))
            .toList()
            .mapWithSource(view.sourceInstanceId, filters.includeSource) { row, source ->
                val metricType = row[view.metricType]
                DailyMetricResponse(
                    id = row[view.id],
                    date = row[view.date].toString(),
                    metricType = metricType,
                    value = row[view.value],
                    unit = DailyMetricRegistry.find(metricType)?.unit.orEmpty(),
                    source = source,
                )
            }
    }
}
