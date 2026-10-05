package me.aquitano.health.application.metric.dashboard

import me.aquitano.health.api.dto.DashboardStepsSummaryResponse
import me.aquitano.health.api.dto.DashboardSummaryResponse
import me.aquitano.health.application.metric.common.QueryParams
import me.aquitano.health.application.metric.common.repository.ReadFilters
import me.aquitano.health.application.metric.common.singleSource
import me.aquitano.health.application.metric.common.toResponse
import me.aquitano.health.application.metric.common.validateDateRange
import me.aquitano.health.application.metric.scalar.ScalarSampleReadRepository
import me.aquitano.health.application.metric.scalar.toScalarResponse
import me.aquitano.health.application.metric.sleep.repository.SleepRepository
import me.aquitano.health.application.metric.steps.repository.CanonicalStepDerivationRepository
import me.aquitano.health.domain.BodyMetricTypes
import me.aquitano.health.domain.ScalarMetricTypes
import me.aquitano.health.infrastructure.database.suspendDbTransaction
import me.aquitano.health.shared.SortDirection
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Instant

class DashboardQueryService(
    private val database: Database,
    private val canonicalStepRepository: CanonicalStepDerivationRepository,
    private val sleepRepository: SleepRepository,
    private val scalarRepository: ScalarSampleReadRepository,
) {
    suspend fun dashboardSummary(
        params: QueryParams,
        now: Instant,
    ): DashboardSummaryResponse {
        val fromDate = params.requiredDate("fromDate")
        val toDate = params.requiredDate("toDate")
        validateDateRange(fromDate, toDate)
        val includeSource = params.boolean("includeSource", default = false)
        val timezone = params.timezone()
        val fromInstant = fromDate.atStartOfDay(timezone).toInstant()
        val toInstant = toDate.plusDays(1).atStartOfDay(timezone).toInstant()

        return suspendDbTransaction(db = database) {
            val filters =
                ReadFilters(
                    from = fromInstant,
                    to = toInstant,
                    fromDate = fromDate,
                    toDate = toDate,
                    timezone = timezone,
                    provider = params.optional("provider"),
                    providerInstanceId = params.optional("providerInstanceId"),
                    includeSource = includeSource,
                    limit = 1,
                    order = SortDirection.Desc,
                )
            val sleepNightFilters =
                ReadFilters(
                    fromDate = toDate,
                    toDate = toDate,
                    timezone = timezone,
                    provider = params.optional("provider"),
                    providerInstanceId = params.optional("providerInstanceId"),
                    includeSource = includeSource,
                    limit = 1,
                    order = SortDirection.Desc,
                )

            DashboardSummaryResponse(
                fromDate = fromDate.toString(),
                toDate = toDate.toString(),
                steps = stepsSummary(filters),
                latestWeight = latestWeight(filters),
                latestHeartRate = latestHeartRate(filters),
                lastSleepSession = lastSleepSession(sleepNightFilters),
            )
        }
    }

    private fun stepsSummary(
        filters: ReadFilters,
    ): DashboardStepsSummaryResponse {
        val (summary, sourceMetadata) =
            canonicalStepRepository.summarizeCanonicalStepsForDashboard(filters)
        return DashboardStepsSummaryResponse(
            steps = summary.steps,
            sampleCount = summary.sampleCount,
            source = summary.sourceInstanceIds.singleSource(sourceMetadata) { it },
        )
    }

    private fun latestWeight(
        filters: ReadFilters,
    ) = run {
        val (row, metadata) =
            scalarRepository.latest(
                filters,
                setOf(BodyMetricTypes.WEIGHT),
                canonical = true,
            )
        row?.toScalarResponse(metadata)
    }

    private fun latestHeartRate(
        filters: ReadFilters,
    ) = run {
        val (row, metadata) =
            scalarRepository.latest(
                filters,
                setOf(ScalarMetricTypes.HEART_RATE),
                canonical = true,
            )
        row?.toScalarResponse(metadata)
    }

    private fun lastSleepSession(
        filters: ReadFilters,
    ) = sleepRepository
        .listCanonicalSleepNights(filters)
        .let { (sleepNights, sleepStagesBySession, sleepSourceMetadata) ->
            val sleep = sleepNights.firstOrNull()?.session
            sleep?.toResponse(sleepStagesBySession, sleepSourceMetadata)
        }
}
