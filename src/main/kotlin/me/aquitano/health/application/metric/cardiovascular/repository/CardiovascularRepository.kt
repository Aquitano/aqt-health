package me.aquitano.health.application.metric.cardiovascular.repository

import me.aquitano.health.api.dto.BloodPressureMeasurementResponse
import me.aquitano.health.api.dto.SourceMetadataResponse
import me.aquitano.health.application.metric.common.keysetFetchLimit
import me.aquitano.health.application.metric.common.repository.*
import me.aquitano.health.application.metric.common.repository.BaseMetricReadRepository
import me.aquitano.health.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

class CardiovascularRepository : BaseMetricReadRepository() {
    fun listBloodPressure(filters: ReadFilters): List<BloodPressureMeasurementResponse> {
        val where =
            timestampConditions(
                filters = filters,
                sourceInstanceIdColumn = BloodPressureMeasurementsTable.sourceInstanceId,
                fromColumn = BloodPressureMeasurementsTable.measuredAt,
            ).whereOrNull() ?: return emptyList()

        val keyset =
            timestampKeyset(
                filters.cursor,
                filters.order,
                BloodPressureMeasurementsTable.measuredAt,
                BloodPressureMeasurementsTable.id,
            )
        return BloodPressureMeasurementsTable
            .selectAll()
            .where(where and (keyset ?: Op.TRUE))
            .orderBy(
                BloodPressureMeasurementsTable.measuredAt to filters.sortOrder(),
                BloodPressureMeasurementsTable.id to filters.sortOrder(),
            ).limit(keysetFetchLimit(filters.limit))
            .toList()
            .mapWithSource(
                BloodPressureMeasurementsTable.sourceInstanceId,
                filters.includeSource,
                ::toBloodPressureMeasurementResponse,
            )
    }

    private fun toBloodPressureMeasurementResponse(
        row: ResultRow,
        source: SourceMetadataResponse?,
    ): BloodPressureMeasurementResponse =
        BloodPressureMeasurementResponse(
            id = row[BloodPressureMeasurementsTable.id].value,
            measuredAt = row[BloodPressureMeasurementsTable.measuredAt].toInstant().toString(),
            systolicMmhg = row[BloodPressureMeasurementsTable.systolicMmhg],
            diastolicMmhg = row[BloodPressureMeasurementsTable.diastolicMmhg],
            heartRateBpm = row[BloodPressureMeasurementsTable.heartRateBpm],
            source = source,
        )
}
