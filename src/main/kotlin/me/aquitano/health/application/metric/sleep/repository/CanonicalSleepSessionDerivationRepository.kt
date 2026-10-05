package me.aquitano.health.application.metric.sleep.repository

import me.aquitano.health.application.metric.common.keysetFetchLimit
import me.aquitano.health.application.metric.common.repository.BaseMetricReadRepository
import me.aquitano.health.application.metric.common.repository.ReadFilters
import me.aquitano.health.application.metric.common.repository.SourceMetadata
import me.aquitano.health.application.metric.common.repository.TimeFilterMode
import me.aquitano.health.infrastructure.database.tables.CanonicalSleepSessionsTable
import me.aquitano.health.infrastructure.database.tables.SleepSessionsTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Reads through the canonical_sleep_sessions view (winning provider per UTC date keeps all sessions, see V15). */
class CanonicalSleepSessionDerivationRepository : BaseMetricReadRepository() {
    fun listCanonicalSleepSessions(
        filters: ReadFilters,
    ): Pair<List<SleepSessionRow>, Map<Int, SourceMetadata>> {
        val where =
            timestampConditions(
                filters = filters,
                sourceInstanceIdColumn = CanonicalSleepSessionsTable.sourceInstanceId,
                fromColumn = CanonicalSleepSessionsTable.startAt,
            ) ?: return emptyReadResult()

        val keyset =
            timestampKeyset(
                filters.cursor,
                filters.order,
                CanonicalSleepSessionsTable.startAt,
                SleepSessionsTable.id,
            )
        val rows =
            CanonicalSleepSessionsTable
                .innerJoin(SleepSessionsTable, { sleepSessionId }, { SleepSessionsTable.id })
                .selectAll()
                .where(keyset?.let { where and it } ?: where)
                .orderBy(
                    CanonicalSleepSessionsTable.startAt to filters.sortOrder(),
                    SleepSessionsTable.id to filters.sortOrder(),
                ).limit(keysetFetchLimit(filters.limit))
                .map(::toSleepSessionRow)
        return rows to sourceMetadata(rows.map { it.sourceInstanceId }.toSet(), filters.includeSource)
    }

    fun avgCanonicalSleepDuration(filters: ReadFilters): Long? {
        val where =
            timestampConditions(
                filters = filters,
                sourceInstanceIdColumn = CanonicalSleepSessionsTable.sourceInstanceId,
                fromColumn = CanonicalSleepSessionsTable.startAt,
                mode = TimeFilterMode.OverlapsWindow(toColumn = CanonicalSleepSessionsTable.endAt, inclusiveFrom = true),
            ) ?: return null

        val avgExpression = SleepSessionsTable.durationSeconds.avg()
        return CanonicalSleepSessionsTable
            .innerJoin(SleepSessionsTable, { sleepSessionId }, { SleepSessionsTable.id })
            .select(avgExpression)
            .where(where)
            .singleOrNull()
            ?.let { it[avgExpression]?.toLong() }
    }
}
