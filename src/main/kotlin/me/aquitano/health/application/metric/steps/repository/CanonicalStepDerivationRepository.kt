package me.aquitano.health.application.metric.steps.repository

import me.aquitano.health.api.dto.StepDailySummaryResponse
import me.aquitano.health.application.metric.common.keysetFetchLimit
import me.aquitano.health.application.metric.common.repository.BaseMetricReadRepository
import me.aquitano.health.application.metric.common.repository.LocalDayOf
import me.aquitano.health.application.metric.common.repository.ReadFilters
import me.aquitano.health.application.metric.common.repository.SourceMetadata
import me.aquitano.health.application.metric.common.repository.TimeFilterMode
import me.aquitano.health.application.metric.common.toResponse
import me.aquitano.health.application.metric.steps.derived.CANONICAL_STEP_ALGORITHM_VERSION
import me.aquitano.health.infrastructure.database.tables.CanonicalStepDayBucketContributionsTable
import me.aquitano.health.infrastructure.database.tables.CanonicalStepSamplesTable
import me.aquitano.health.infrastructure.database.tables.IngestionRecordsTable
import me.aquitano.health.infrastructure.database.tables.StepSamplesTable
import me.aquitano.health.infrastructure.database.toDbTimestamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

data class CanonicalStepSampleOutput(
    val sampleId: Int,
    val sourceInstanceId: Int,
    val startAt: Instant,
    val endAt: Instant,
    val steps: Int,
)

data class CanonicalStepBucketContributionOutput(
    val date: LocalDate,
    val sourceInstanceId: Int,
    val sampleId: Int,
    val bucketStartAt: Instant,
    val bucketEndAt: Instant,
    val value: Double,
    val computedAt: Instant,
)

data class CanonicalStepOutput(
    val date: LocalDate,
    val algorithmVersion: Int,
    val computedAt: Instant,
    val samples: List<CanonicalStepSampleOutput>,
    val bucketContributions: List<CanonicalStepBucketContributionOutput>,
)

data class StepBucketContributionRow(
    val bucketStartAt: Instant,
    val bucketEndAt: Instant,
    val value: Double,
)

data class CanonicalDashboardStepsSummary(
    val steps: Int,
    val sampleCount: Int,
    val sourceInstanceIds: Set<Int>,
)

class CanonicalStepDerivationRepository : BaseMetricReadRepository() {
    fun listRawSamplesForDay(
        dayStart: Instant,
        dayEnd: Instant,
    ): List<StepSampleRow> =
        StepSamplesTable
            .leftJoin(IngestionRecordsTable, { ingestionRecordId }, { IngestionRecordsTable.id })
            .select(StepSamplesTable.columns + IngestionRecordsTable.googleStepAllocationPriority)
            .where {
                (StepSamplesTable.startAt less dayEnd.toDbTimestamp()) and
                    (StepSamplesTable.endAt greater dayStart.toDbTimestamp())
            }.orderBy(StepSamplesTable.startAt to SortOrder.ASC, StepSamplesTable.id to SortOrder.ASC)
            .map(::toStepSampleRow)

    fun rawSampleIdsForDay(
        dayStart: Instant,
        dayEnd: Instant,
    ): Set<Int> =
        StepSamplesTable
            .select(StepSamplesTable.id)
            .where {
                (StepSamplesTable.startAt less dayEnd.toDbTimestamp()) and
                    (StepSamplesTable.endAt greater dayStart.toDbTimestamp())
            }.mapTo(hashSetOf()) { it[StepSamplesTable.id].value }

    fun persistCanonicalOutput(output: CanonicalStepOutput) {
        CanonicalStepDayBucketContributionsTable.deleteWhere {
            (CanonicalStepDayBucketContributionsTable.date eq output.date) and
                (CanonicalStepDayBucketContributionsTable.algorithmVersion eq output.algorithmVersion)
        }
        CanonicalStepSamplesTable.deleteWhere {
            (CanonicalStepSamplesTable.date eq output.date) and
                (CanonicalStepSamplesTable.algorithmVersion eq output.algorithmVersion)
        }
        CanonicalStepSamplesTable.batchInsert(
            output.samples,
            useMultiRowValues = true,
            ignore = true,
            shouldReturnGeneratedValues = false,
        ) { sample ->
            this[CanonicalStepSamplesTable.date] = output.date
            this[CanonicalStepSamplesTable.sourceInstanceId] = sample.sourceInstanceId
            this[CanonicalStepSamplesTable.stepSampleId] = sample.sampleId
            this[CanonicalStepSamplesTable.startAt] = sample.startAt.toDbTimestamp()
            this[CanonicalStepSamplesTable.endAt] = sample.endAt.toDbTimestamp()
            this[CanonicalStepSamplesTable.steps] = sample.steps
            this[CanonicalStepSamplesTable.algorithmVersion] = output.algorithmVersion
            this[CanonicalStepSamplesTable.computedAt] = output.computedAt.toDbTimestamp()
        }
        CanonicalStepDayBucketContributionsTable.batchInsert(
            output.bucketContributions,
            useMultiRowValues = true,
            ignore = true,
            shouldReturnGeneratedValues = false,
        ) { contribution ->
            this[CanonicalStepDayBucketContributionsTable.date] = contribution.date
            this[CanonicalStepDayBucketContributionsTable.sourceInstanceId] = contribution.sourceInstanceId
            this[CanonicalStepDayBucketContributionsTable.stepSampleId] = contribution.sampleId
            this[CanonicalStepDayBucketContributionsTable.bucketStartAt] = contribution.bucketStartAt.toDbTimestamp()
            this[CanonicalStepDayBucketContributionsTable.bucketEndAt] = contribution.bucketEndAt.toDbTimestamp()
            this[CanonicalStepDayBucketContributionsTable.value] = contribution.value
            this[CanonicalStepDayBucketContributionsTable.algorithmVersion] = output.algorithmVersion
            this[CanonicalStepDayBucketContributionsTable.computedAt] = contribution.computedAt.toDbTimestamp()
        }
    }

    fun listCanonicalStepSamples(
        filters: ReadFilters,
        algorithmVersion: Int,
    ): Pair<List<StepSampleRow>, Map<Int, SourceMetadata>> {
        val where =
            timestampConditions(
                filters = filters,
                sourceInstanceIdColumn = CanonicalStepSamplesTable.sourceInstanceId,
                fromColumn = CanonicalStepSamplesTable.startAt,
            ).whereOrNull() ?: return emptyReadResult()

        val keyset =
            timestampKeyset(
                filters.cursor,
                filters.order,
                CanonicalStepSamplesTable.startAt,
                StepSamplesTable.id,
            )
        val rows =
            CanonicalStepSamplesTable
                .innerJoin(StepSamplesTable, { stepSampleId }, { StepSamplesTable.id })
                .selectAll()
                .where(
                    where and (CanonicalStepSamplesTable.algorithmVersion eq algorithmVersion) and
                        firstDateForSample(algorithmVersion) and (keyset ?: Op.TRUE),
                ).orderBy(
                    CanonicalStepSamplesTable.startAt to filters.sortOrder(),
                    StepSamplesTable.id to filters.sortOrder(),
                ).limit(keysetFetchLimit(filters.limit))
                .map(::toStepSampleRow)
        return rows to sourceMetadata(rows.map { it.sourceInstanceId }.toSet(), filters.includeSource)
    }

    /** Canonical samples overlapping the filter window, counted per source instance. */
    fun countCanonicalStepSamplesBySource(
        filters: ReadFilters,
        algorithmVersion: Int,
    ): Pair<Map<Int, Int>, Map<Int, SourceMetadata>> {
        val where =
            timestampConditions(
                filters = filters,
                sourceInstanceIdColumn = CanonicalStepSamplesTable.sourceInstanceId,
                fromColumn = CanonicalStepSamplesTable.startAt,
                toColumn = CanonicalStepSamplesTable.endAt,
                mode = TimeFilterMode.OVERLAPS_WINDOW,
            ).whereOrNull() ?: return emptyMap<Int, Int>() to emptyMap()

        val count = CanonicalStepSamplesTable.id.count()
        val counts =
            CanonicalStepSamplesTable
                .select(CanonicalStepSamplesTable.sourceInstanceId, count)
                .where(
                    where and (CanonicalStepSamplesTable.algorithmVersion eq algorithmVersion) and
                        firstDateForSample(algorithmVersion),
                ).groupBy(CanonicalStepSamplesTable.sourceInstanceId)
                .associate { it[CanonicalStepSamplesTable.sourceInstanceId] to it[count].toInt() }
        return counts to sourceMetadata(counts.keys, filters.includeSource)
    }

    /**
     * canonical_step_samples holds one row per (date, step_sample_id), so a sample crossing UTC
     * midnight is stored under both dates. Reads keep only its earliest date, otherwise the same
     * underlying sample is returned twice.
     */
    private fun firstDateForSample(algorithmVersion: Int): Op<Boolean> {
        val earlier = CanonicalStepSamplesTable.alias("canonical_step_samples_earlier")
        return notExists(
            earlier
                .select(earlier[CanonicalStepSamplesTable.id])
                .where {
                    (earlier[CanonicalStepSamplesTable.stepSampleId] eq CanonicalStepSamplesTable.stepSampleId) and
                        (earlier[CanonicalStepSamplesTable.algorithmVersion] eq algorithmVersion) and
                        (earlier[CanonicalStepSamplesTable.date] less CanonicalStepSamplesTable.date)
                },
        )
    }

    /**
     * All canonical totals sum the same 15-minute bucket contributions, grouped by the local day of
     * [ReadFilters.timezone]. Buckets are aligned to UTC quarter hours and every zone offset is a
     * whole number of quarter hours, so no bucket straddles a local midnight.
     */
    private fun localDayContributionConditions(filters: ReadFilters): Op<Boolean>? {
        fun startOf(date: LocalDate?) = date?.atStartOfDay(filters.timezone)?.toInstant()
        return timestampConditions(
            filters = filters.copy(from = startOf(filters.fromDate), to = startOf(filters.toDate?.plusDays(1))),
            sourceInstanceIdColumn = CanonicalStepDayBucketContributionsTable.sourceInstanceId,
            fromColumn = CanonicalStepDayBucketContributionsTable.bucketStartAt,
        ).whereOrNull()
    }

    private fun localDayOf(filters: ReadFilters) = LocalDayOf(CanonicalStepDayBucketContributionsTable.bucketStartAt, filters.timezone.id)

    fun sumCanonicalStepDailySummaries(filters: ReadFilters): DashboardStepsSummaryRow {
        val table = CanonicalStepDayBucketContributionsTable
        val where =
            localDayContributionConditions(filters)
                ?: return DashboardStepsSummaryRow(steps = 0, dayCount = 0)
        val total = table.value.sum()
        val days = Count(localDayOf(filters), distinct = true)
        val row =
            table
                .select(total, days)
                .where(where and (table.algorithmVersion eq CANONICAL_STEP_ALGORITHM_VERSION))
                .single()
        return DashboardStepsSummaryRow(
            steps = (row[total] ?: 0.0).roundToInt(),
            dayCount = row[days].toInt(),
        )
    }

    fun listCanonicalStepDailySummaries(filters: ReadFilters): List<StepDailySummaryResponse> {
        val table = CanonicalStepDayBucketContributionsTable
        val where = localDayContributionConditions(filters) ?: return emptyList()
        val day = localDayOf(filters)
        // One row per date means the date alone determines the cursor position.
        val keyset = dateKeyset(filters.cursor, filters.order, day, intLiteral(0))
        val total = table.value.sum()
        val samples = table.stepSampleId.countDistinct()
        val sources = table.sourceInstanceId.countDistinct()
        val source = table.sourceInstanceId.min()
        val rows =
            table
                .select(day, total, samples, sources, source)
                .where(where and (table.algorithmVersion eq CANONICAL_STEP_ALGORITHM_VERSION) and (keyset ?: Op.TRUE))
                .groupBy(day)
                .orderBy(day to filters.sortOrder())
                .limit(keysetFetchLimit(filters.limit))
                .toList()
        val singleSourceId = { row: ResultRow -> if (row[sources] == 1L) row[source] else null }
        val metadata = sourceMetadata(rows.mapNotNullTo(HashSet(), singleSourceId), filters.includeSource)
        return rows.map {
            StepDailySummaryResponse(
                date = it[day].toString(),
                steps = (it[total] ?: 0.0).roundToInt(),
                sampleCount = it[samples].toInt(),
                source = singleSourceId(it)?.let(metadata::get).toResponse(),
            )
        }
    }

    fun listBucketContributions(
        filters: ReadFilters,
        algorithmVersion: Int,
    ): List<StepBucketContributionRow> {
        val where =
            timestampConditions(
                filters = filters,
                sourceInstanceIdColumn = CanonicalStepDayBucketContributionsTable.sourceInstanceId,
                fromColumn = CanonicalStepDayBucketContributionsTable.bucketStartAt,
            ).whereOrNull() ?: return emptyList()

        return CanonicalStepDayBucketContributionsTable
            .selectAll()
            .where(where and (CanonicalStepDayBucketContributionsTable.algorithmVersion eq algorithmVersion))
            .orderBy(CanonicalStepDayBucketContributionsTable.bucketStartAt to SortOrder.ASC)
            .map {
                StepBucketContributionRow(
                    bucketStartAt = it[CanonicalStepDayBucketContributionsTable.bucketStartAt].toInstant(),
                    bucketEndAt = it[CanonicalStepDayBucketContributionsTable.bucketEndAt].toInstant(),
                    value = it[CanonicalStepDayBucketContributionsTable.value],
                )
            }
    }

    fun summarizeCanonicalStepsForDashboard(
        filters: ReadFilters,
        algorithmVersion: Int,
    ): Pair<CanonicalDashboardStepsSummary, Map<Int, SourceMetadata>> {
        val table = CanonicalStepDayBucketContributionsTable
        val where = localDayContributionConditions(filters) ?: return emptyDashboardStepSummary(filters.includeSource)
        val stepsExpression = table.value.sum()
        val samplesExpression = table.stepSampleId.countDistinct()
        val rows =
            table
                .select(table.sourceInstanceId, stepsExpression, samplesExpression)
                .where(where and (table.algorithmVersion eq algorithmVersion))
                .groupBy(table.sourceInstanceId)
                .toList()
        val sourceIds = rows.mapTo(HashSet()) { it[table.sourceInstanceId] }
        val summary =
            CanonicalDashboardStepsSummary(
                steps = rows.sumOf { it[stepsExpression] ?: 0.0 }.roundToInt(),
                sampleCount = rows.sumOf { it[samplesExpression].toInt() },
                sourceInstanceIds = sourceIds,
            )
        return summary to sourceMetadata(sourceIds, filters.includeSource)
    }

    private fun emptyDashboardStepSummary(
        includeSource: Boolean,
    ): Pair<CanonicalDashboardStepsSummary, Map<Int, SourceMetadata>> =
        CanonicalDashboardStepsSummary(
            steps = 0,
            sampleCount = 0,
            sourceInstanceIds = emptySet(),
        ) to sourceMetadata(emptySet(), includeSource)
}
