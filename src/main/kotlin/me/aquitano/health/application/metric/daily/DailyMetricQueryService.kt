package me.aquitano.health.application.metric.daily

import me.aquitano.health.api.dto.DailyMetricsResponse
import me.aquitano.health.application.metric.common.QueryParams
import me.aquitano.health.application.metric.common.SortFields
import me.aquitano.health.application.metric.common.dailyReadFilters
import me.aquitano.health.application.metric.common.pagedRead
import me.aquitano.health.application.metric.daily.repository.DailyMetricReadRepository
import me.aquitano.health.domain.DailyMetricRegistry
import me.aquitano.health.domain.RequestValidationException
import me.aquitano.health.domain.ValidationIssueCodes
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Instant

class DailyMetricQueryService(
    private val database: Database,
    private val repository: DailyMetricReadRepository,
) {
    suspend fun listDailyMetrics(
        params: QueryParams,
        now: Instant,
    ): DailyMetricsResponse {
        params.rejectLatest()
        val metricTypes = params.metricTypes()
        val filters = params.dailyReadFilters(now)
        return pagedRead(database, filters, SortFields.DATE, { it.date }, { it.id.toLong() }, ::DailyMetricsResponse) {
            repository.listCanonical(filters, metricTypes)
        }
    }

    private fun QueryParams.metricTypes(): Set<String> {
        val requested =
            optional("metricTypes")
                ?.split(",")
                ?.map { it.trim() }
                ?.filterTo(linkedSetOf()) { it.isNotEmpty() }
                ?: return DailyMetricRegistry.metricTypes
        val unknown = requested.filter { DailyMetricRegistry.find(it) == null }
        if (unknown.isNotEmpty()) {
            throw RequestValidationException(
                field = "metricTypes",
                code = ValidationIssueCodes.UnsupportedValue,
                message = "unknown daily metric types ${unknown.joinToString(", ")}",
            )
        }
        return requested
    }
}
