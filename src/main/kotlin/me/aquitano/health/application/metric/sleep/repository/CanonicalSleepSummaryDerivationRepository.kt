package me.aquitano.health.application.metric.sleep.repository

import me.aquitano.health.application.metric.common.keysetFetchLimit
import me.aquitano.health.application.metric.common.repository.ReadFilters
import me.aquitano.health.application.metric.common.repository.SourceMetadata
import me.aquitano.health.infrastructure.database.tables.CanonicalSleepSummariesTable
import me.aquitano.health.infrastructure.database.tables.SleepSummariesTable
import me.aquitano.health.application.metric.common.repository.BaseMetricReadRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Reads through the canonical_sleep_summaries view (rank winner per UTC start date, see V15). */
class CanonicalSleepSummaryDerivationRepository : BaseMetricReadRepository() {
    fun listCanonicalSleepSummaries(
        filters: ReadFilters,
    ): Pair<List<SleepSummaryRow>, Map<Int, SourceMetadata>> {
        val where = timestampConditions(
            filters = filters,
            sourceInstanceIdColumn = CanonicalSleepSummariesTable.sourceInstanceId,
            fromColumn = CanonicalSleepSummariesTable.startAt,
        ).whereOrNull() ?: return emptyReadResult()

        val keyset = timestampKeyset(
            filters.cursor,
            filters.order,
            CanonicalSleepSummariesTable.endAt,
            SleepSummariesTable.id,
        )
        val rows = CanonicalSleepSummariesTable
            .innerJoin(SleepSummariesTable, { sleepSummaryId }, { SleepSummariesTable.id })
            .selectAll()
            .where(keyset?.let { where and it } ?: where)
            .orderBy(
                CanonicalSleepSummariesTable.endAt to filters.sortOrder(),
                SleepSummariesTable.id to filters.sortOrder(),
            )
            .limit(keysetFetchLimit(filters.limit))
            .map(::toSleepSummaryRow)
        return rows to sourceMetadata(rows.map { it.sourceInstanceId }.toSet(), filters.includeSource)
    }
}
