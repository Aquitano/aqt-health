package me.aquitano.health.application.metric.steps

import me.aquitano.health.api.dto.StepDailySummariesResponse
import me.aquitano.health.api.dto.StepDailySummaryResponse
import me.aquitano.health.api.dto.StepSampleResponse
import me.aquitano.health.api.dto.StepSamplesResponse
import me.aquitano.health.application.metric.common.QueryParamSpecs
import me.aquitano.health.application.metric.common.QueryParams
import me.aquitano.health.application.metric.common.dailyReadFilters
import me.aquitano.health.application.metric.common.keysetPage
import me.aquitano.health.application.metric.common.meta
import me.aquitano.health.application.metric.common.readFilters
import me.aquitano.health.application.metric.common.toResponse
import me.aquitano.health.application.metric.steps.derived.CANONICAL_STEP_ALGORITHM_VERSION
import me.aquitano.health.application.metric.steps.repository.CanonicalStepDerivationRepository
import me.aquitano.health.infrastructure.database.suspendDbTransaction
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Instant

class StepQueryService(
    private val database: Database,
    private val canonicalRepository: CanonicalStepDerivationRepository,
) {
    suspend fun listStepSamples(params: QueryParams): StepSamplesResponse =
        suspendDbTransaction(db = database) {
            val filters = params.readFilters(
                sortSpec = QueryParamSpecs.sortByStartAt,
                latestSupported = true,
            )
            val (rows, sourceMetadata) =
                canonicalRepository.listCanonicalStepSamples(filters, CANONICAL_STEP_ALGORITHM_VERSION)
            val page = rows.keysetPage(
                limit = filters.limit,
                sort = filters.sort,
                order = filters.order,
                sortValue = { it.startAt },
                id = { it.id.toLong() },
            )
            StepSamplesResponse(
                items = page.items.map {
                    StepSampleResponse(
                        id = it.id,
                        startAt = it.startAt,
                        endAt = it.endAt,
                        steps = it.steps,
                        source = sourceMetadata[it.sourceInstanceId].toResponse(),
                    )
                },
                meta = page.items.meta(filters, page.nextCursor),
            )
        }

    suspend fun listStepDailySummaries(
        params: QueryParams,
        now: Instant,
    ): StepDailySummariesResponse =
        suspendDbTransaction(db = database) {
            params.rejectLatest()
            val filters = params.dailyReadFilters(now)
            val (rows, sourceMetadata) = canonicalRepository.listCanonicalStepDailySummaries(filters)
            val page = rows.keysetPage(
                limit = filters.limit,
                sort = filters.sort,
                order = filters.order,
                sortValue = { it.date },
                id = { it.id.toLong() },
            )
            StepDailySummariesResponse(
                items = page.items.map {
                    StepDailySummaryResponse(
                        date = it.date,
                        steps = it.steps,
                        sampleCount = it.sampleCount,
                        source = sourceMetadata[it.sourceInstanceId].toResponse(),
                    )
                },
                meta = page.items.meta(filters, page.nextCursor),
            )
        }
}

