package me.aquitano.health.application

import me.aquitano.health.api.dto.SleepSummariesResponse
import me.aquitano.health.application.metric.common.QueryParams
import me.aquitano.health.application.metric.common.SortFields
import me.aquitano.health.application.metric.common.pagedRead
import me.aquitano.health.application.metric.common.readFilters
import me.aquitano.health.application.metric.common.toResponse
import me.aquitano.health.application.metric.sleep.repository.CanonicalSleepSummaryDerivationRepository
import org.jetbrains.exposed.v1.jdbc.Database

class SleepSummaryReadService(
    private val database: Database,
    private val canonicalRepository: CanonicalSleepSummaryDerivationRepository,
) {
    suspend fun list(params: QueryParams): SleepSummariesResponse {
        val filters = params.readFilters()
        return pagedRead(database, filters, SortFields.END_AT, { it.endAt }, { it.id.toLong() }, ::SleepSummariesResponse) {
            val (rows, sourceMetadata) = canonicalRepository.listCanonicalSleepSummaries(filters)
            rows.map { it.toResponse(sourceMetadata) }
        }
    }
}
