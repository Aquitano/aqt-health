package me.aquitano.health.application.metric.scalar

import me.aquitano.health.api.dto.MetricCatalogEntryResponse
import me.aquitano.health.api.dto.MetricTypeCatalogResponse
import me.aquitano.health.api.dto.ReadResponseMeta
import me.aquitano.health.api.dto.ScalarDailySummariesResponse
import me.aquitano.health.api.dto.ScalarDailySummaryResponse
import me.aquitano.health.api.dto.ScalarSamplesResponse
import me.aquitano.health.api.dto.ScalarSummaryResponse
import me.aquitano.health.application.metric.common.Orders
import me.aquitano.health.application.metric.common.QueryParamSpecs
import me.aquitano.health.application.metric.common.QueryParams
import me.aquitano.health.application.metric.common.SortFields
import me.aquitano.health.application.metric.common.pagedRead
import me.aquitano.health.application.metric.common.readFilters
import me.aquitano.health.application.metric.common.summaryFilters
import me.aquitano.health.domain.NotFoundException
import me.aquitano.health.domain.RequestValidationException
import me.aquitano.health.domain.ScalarMetricRegistry
import me.aquitano.health.domain.ValidationIssue
import me.aquitano.health.domain.ValidationIssueCodes
import me.aquitano.health.infrastructure.database.suspendDbTransaction
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * The one read surface for all scalar metrics: catalog from the registry, canonical
 * (or raw=true) keyset-paginated lists per metric type, and per-type summaries.
 * Unknown metric types are a 404, matching the catalog as the source of truth.
 */
class ScalarMetricQueryService(
    private val database: Database,
    private val scalarRepository: ScalarSampleReadRepository,
) {
    fun catalog(): MetricTypeCatalogResponse =
        MetricTypeCatalogResponse(
            items =
                ScalarMetricRegistry.descriptors.map {
                    MetricCatalogEntryResponse(
                        metricType = it.metricType,
                        family = it.family,
                        unit = it.unit,
                        supportsSegment = it.supportsSegment,
                        contexts = it.allowedContexts?.sorted(),
                    )
                },
        )

    suspend fun list(
        metricType: String,
        params: QueryParams,
    ): ScalarSamplesResponse {
        requireKnown(metricType)
        return list(setOf(metricType), params)
    }

    suspend fun listAcrossTypes(params: QueryParams): ScalarSamplesResponse = list(params.metricTypes(), params)

    private suspend fun list(
        metricTypes: Set<String>,
        params: QueryParams,
    ): ScalarSamplesResponse {
        val raw = params.boolean(QueryParamSpecs.raw)
        val filters = params.readFilters()
        return pagedRead(database, filters, SortFields.MEASURED_AT, { it.measuredAt }, { it.id }, ::ScalarSamplesResponse) {
            val (rows, sourceMetadata) = scalarRepository.list(filters, metricTypes, canonical = !raw)
            rows.map { it.toScalarResponse(sourceMetadata) }
        }
    }

    private fun QueryParams.metricTypes(): Set<String> {
        val metricTypes =
            required("metricTypes")
                .split(",")
                .map { it.trim() }
                .filterTo(linkedSetOf()) { it.isNotEmpty() }
        val unknown = metricTypes.filter { ScalarMetricRegistry.find(it) == null }
        if (metricTypes.isEmpty() || unknown.isNotEmpty()) {
            throw RequestValidationException(
                field = "metricTypes",
                code = ValidationIssueCodes.UnsupportedValue,
                message =
                    if (unknown.isEmpty()) {
                        "must contain at least one metric type"
                    } else {
                        "unknown metric types ${unknown.joinToString(", ")}"
                    },
            )
        }
        return metricTypes
    }

    suspend fun summary(
        metricType: String,
        params: QueryParams,
    ): ScalarSummaryResponse {
        requireKnown(metricType)
        return suspendDbTransaction(db = database) {
            val filters = params.summaryFilters()
            val summary = scalarRepository.summarize(filters, setOf(metricType), canonical = true)
            val (latest, sourceMetadata) =
                scalarRepository.latest(filters, setOf(metricType), canonical = true)
            ScalarSummaryResponse(
                metricType = metricType,
                count = summary.count,
                minValue = summary.minValue,
                maxValue = summary.maxValue,
                avgValue = summary.avgValue,
                latest = latest?.toScalarResponse(sourceMetadata),
            )
        }
    }

    suspend fun summaryDaily(
        metricType: String,
        params: QueryParams,
    ): ScalarDailySummariesResponse {
        requireKnown(metricType)
        return suspendDbTransaction(db = database) {
            val filters = params.summaryFilters()
            if (filters.from == null && filters.to == null) {
                throw RequestValidationException(field = "from", code = ValidationIssueCodes.Required, message = "at least one of from or to is required")
            }
            val zone = params.timezone()
            val items =
                scalarRepository
                    .summarizeDaily(filters, setOf(metricType), canonical = true, zone)
                    .map {
                        ScalarDailySummaryResponse(
                            date = it.date.toString(),
                            count = it.count,
                            minValue = it.minValue,
                            maxValue = it.maxValue,
                            avgValue = it.avgValue,
                        )
                    }
            ScalarDailySummariesResponse(
                items = items,
                meta =
                    ReadResponseMeta(
                        count = items.size,
                        limit = items.size,
                        sort = SortFields.DATE,
                        order = Orders.ASC,
                    ),
            )
        }
    }

    private fun requireKnown(metricType: String) {
        if (ScalarMetricRegistry.find(metricType) == null) {
            throw NotFoundException("Unknown metric type '$metricType'")
        }
    }
}
