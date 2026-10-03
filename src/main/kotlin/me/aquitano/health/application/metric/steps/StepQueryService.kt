package me.aquitano.health.application.metric.steps

import me.aquitano.health.api.dto.StepDailySummariesResponse
import me.aquitano.health.api.dto.StepDailySummaryResponse
import me.aquitano.health.api.dto.StepSampleResponse
import me.aquitano.health.api.dto.StepSamplesResponse
import me.aquitano.health.application.metric.common.QueryParamSpecs
import me.aquitano.health.application.metric.common.QueryParams
import me.aquitano.health.application.metric.common.dailyReadFilters
import me.aquitano.health.application.metric.common.pagedRead
import me.aquitano.health.application.metric.common.readFilters
import me.aquitano.health.application.metric.common.toResponse
import me.aquitano.health.application.metric.steps.derived.CANONICAL_STEP_ALGORITHM_VERSION
import me.aquitano.health.application.metric.steps.repository.CanonicalStepDerivationRepository
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Instant

class StepQueryService(
    private val database: Database,
    private val canonicalRepository: CanonicalStepDerivationRepository,
) {
    suspend fun listStepSamples(params: QueryParams): StepSamplesResponse {
        val filters = params.readFilters(sortSpec = QueryParamSpecs.sortByStartAt)
        return pagedRead(database, filters, { it.startAt }, { it.id.toLong() }, ::StepSamplesResponse) {
            val (rows, sourceMetadata) =
                canonicalRepository.listCanonicalStepSamples(filters, CANONICAL_STEP_ALGORITHM_VERSION)
            rows.map {
                StepSampleResponse(
                    id = it.id,
                    startAt = it.startAt.toString(),
                    endAt = it.endAt.toString(),
                    steps = it.steps,
                    source = sourceMetadata[it.sourceInstanceId].toResponse(),
                )
            }
        }
    }

    suspend fun listStepDailySummaries(
        params: QueryParams,
        now: Instant,
    ): StepDailySummariesResponse {
        params.rejectLatest()
        val filters = params.dailyReadFilters(now)
        return pagedRead(database, filters, { it.date }, { 0L }, ::StepDailySummariesResponse) {
            val (rows, sourceMetadata) = canonicalRepository.listCanonicalStepDailySummaries(filters)
            rows.map {
                StepDailySummaryResponse(
                    date = it.date,
                    steps = it.steps,
                    sampleCount = it.sampleCount,
                    source = sourceMetadata[it.sourceInstanceId].toResponse(),
                )
            }
        }
    }
}
