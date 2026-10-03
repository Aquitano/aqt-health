package me.aquitano.health.application.metric.cardiovascular

import me.aquitano.health.api.dto.BloodPressureMeasurementsResponse
import me.aquitano.health.application.metric.cardiovascular.repository.CardiovascularRepository
import me.aquitano.health.application.metric.common.QueryParams
import me.aquitano.health.application.metric.common.SortFields
import me.aquitano.health.application.metric.common.pagedRead
import me.aquitano.health.application.metric.common.readFilters
import me.aquitano.health.application.metric.common.toResponse
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Blood pressure is the one structural cardiovascular metric (paired systolic/diastolic
 * values); the scalar cardiovascular metrics are served by ScalarMetricQueryService.
 */
class CardiovascularQueryService(
    private val database: Database,
    private val cardiovascularRepository: CardiovascularRepository,
) {
    suspend fun listBloodPressure(params: QueryParams): BloodPressureMeasurementsResponse {
        val filters = params.readFilters()
        return pagedRead(database, filters, SortFields.MEASURED_AT, { it.measuredAt }, { it.id.toLong() }, ::BloodPressureMeasurementsResponse) {
            val (rows, sourceMetadata) = cardiovascularRepository.listBloodPressure(filters)
            rows.map { it.toResponse(sourceMetadata) }
        }
    }
}
