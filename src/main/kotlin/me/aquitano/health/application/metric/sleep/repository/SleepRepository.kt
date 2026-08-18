package me.aquitano.health.application.metric.sleep.repository

import me.aquitano.health.application.metric.common.keysetFetchLimit
import me.aquitano.health.application.metric.common.repository.*
import me.aquitano.health.infrastructure.database.tables.*
import me.aquitano.health.infrastructure.database.toApiString
import me.aquitano.health.application.metric.common.repository.BaseMetricReadRepository
import me.aquitano.health.application.metric.common.repository.LocalDayOf
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.*

class SleepRepository : BaseMetricReadRepository() {
    /**
     * Sleep nights are not stored: a night is the canonical sleep session labelled with the
     * local date it ended on, so the label is computed in the requested timezone at read time
     * and the session id doubles as the night id for cursor pagination.
     */
    fun listCanonicalSleepNights(filters: SleepNightReadFilters): Triple<List<SleepNightRow>, Map<Int, List<SleepStageRow>>, Map<Int, SourceMetadata>> {
        val sourceIds = filters.sourceInstanceIds()
        if (sourceIds.hasNoMatchingSources()) return emptyTripleReadResult()

        val nightDate = LocalDayOf(CanonicalSleepSessionsTable.endAt, filters.timezone.id)
        val conditions = mutableListOf<Op<Boolean>>()
        filters.fromDate?.let { conditions.add(nightDate greaterEq it) }
        filters.toDate?.let { conditions.add(nightDate lessEq it) }
        sourceIds?.let { conditions.add(CanonicalSleepSessionsTable.sourceInstanceId inList it) }
        dateKeyset(
            filters.cursor,
            filters.order,
            nightDate,
            CanonicalSleepSessionsTable.id,
        )?.let { conditions.add(it) }

        val nights = CanonicalSleepSessionsTable
            .innerJoin(SleepSessionsTable)
            .select(SleepSessionsTable.columns + nightDate)
            .where { combineConditions(conditions) }
            .orderBy(
                nightDate to filters.sortOrder(),
                CanonicalSleepSessionsTable.id to filters.sortOrder(),
            )
            .limit(keysetFetchLimit(filters.limit))
            .map {
                val session = toSleepSessionRow(it)
                SleepNightRow(
                    id = session.id,
                    date = it[nightDate].toString(),
                    timezone = filters.timezone.id,
                    session = session,
                )
            }
        val stagesBySession = sleepStagesBySession(nights.map { it.session.id })
        val metadata = sourceMetadata(
            nights.map { it.session.sourceInstanceId }.toSet(),
            filters.includeSource
        )
        return Triple(nights, stagesBySession, metadata)
    }

    private fun toSleepStageRow(row: ResultRow): SleepStageRow =
        SleepStageRow(
            stage = row[SleepStagesTable.stage],
            startAt = row[SleepStagesTable.startAt].toApiString(),
            endAt = row[SleepStagesTable.endAt].toApiString(),
            durationSeconds = row[SleepStagesTable.durationSeconds],
        )

    private fun sleepStagesBySession(sessionIds: List<Int>): Map<Int, List<SleepStageRow>> {
        if (sessionIds.isEmpty()) return emptyMap()
        return SleepStagesTable.selectAll()
            .where { SleepStagesTable.sleepSessionId inList sessionIds }
            .orderBy(SleepStagesTable.startAt to SortOrder.ASC)
            .groupBy(
                keySelector = { it[SleepStagesTable.sleepSessionId] },
                valueTransform = ::toSleepStageRow,
            )
    }
}
