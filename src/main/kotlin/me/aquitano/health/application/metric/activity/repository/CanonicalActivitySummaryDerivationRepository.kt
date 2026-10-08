package me.aquitano.health.application.metric.activity.repository

import me.aquitano.health.api.dto.ActivitySummaryResponse
import me.aquitano.health.api.dto.SourceMetadataResponse
import me.aquitano.health.application.metric.common.keysetFetchLimit
import me.aquitano.health.application.metric.common.repository.BaseMetricReadRepository
import me.aquitano.health.application.metric.common.repository.ReadFilters
import me.aquitano.health.infrastructure.database.tables.ActivitySummariesTable
import me.aquitano.health.infrastructure.database.tables.CanonicalActivitySummariesTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Reads through the canonical_activity_summaries view (rank winner per date, see V15). */
class CanonicalActivitySummaryDerivationRepository : BaseMetricReadRepository() {
    fun listCanonicalActivitySummaries(filters: ReadFilters): List<ActivitySummaryResponse> {
        val where =
            dateConditions(
                filters = filters,
                sourceInstanceIdColumn = CanonicalActivitySummariesTable.sourceInstanceId,
                dateColumn = CanonicalActivitySummariesTable.date,
            ) ?: return emptyList()

        val keyset =
            dateKeyset(
                filters.cursor,
                filters.order,
                ActivitySummariesTable.date,
                ActivitySummariesTable.id,
            )
        return CanonicalActivitySummariesTable
            .innerJoin(ActivitySummariesTable, { activitySummaryId }, { ActivitySummariesTable.id })
            .selectAll()
            .where(keyset?.let { where and it } ?: where)
            .orderBy(
                ActivitySummariesTable.date to filters.sortOrder(),
                ActivitySummariesTable.id to filters.sortOrder(),
            ).limit(keysetFetchLimit(filters.limit))
            .toList()
            .mapWithSource(ActivitySummariesTable.sourceInstanceId, filters.includeSource, ::toActivitySummaryResponse)
    }

    private fun toActivitySummaryResponse(
        row: ResultRow,
        source: SourceMetadataResponse?,
    ): ActivitySummaryResponse =
        ActivitySummaryResponse(
            id = row[ActivitySummariesTable.id].value,
            date = row[ActivitySummariesTable.date].toString(),
            distanceMeters = row[ActivitySummariesTable.distanceMeters],
            activeEnergyKcal = row[ActivitySummariesTable.activeEnergyKcal],
            totalEnergyKcal = row[ActivitySummariesTable.totalEnergyKcal],
            elevationMeters = row[ActivitySummariesTable.elevationMeters],
            softMinutes = row[ActivitySummariesTable.softMinutes],
            moderateMinutes = row[ActivitySummariesTable.moderateMinutes],
            intenseMinutes = row[ActivitySummariesTable.intenseMinutes],
            activeMinutes = row[ActivitySummariesTable.activeMinutes],
            averageHeartRateBpm = row[ActivitySummariesTable.avgHeartRateBpm],
            minHeartRateBpm = row[ActivitySummariesTable.minHeartRateBpm],
            maxHeartRateBpm = row[ActivitySummariesTable.maxHeartRateBpm],
            source = source,
        )
}
