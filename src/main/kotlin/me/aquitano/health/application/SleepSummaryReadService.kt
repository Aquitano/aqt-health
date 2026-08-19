package me.aquitano.health.application

import me.aquitano.health.api.dto.SleepSummariesResponse
import me.aquitano.health.application.metric.common.QueryParamSpecs
import me.aquitano.health.application.metric.common.QueryParams
import me.aquitano.health.application.metric.common.keysetPage
import me.aquitano.health.application.metric.common.meta
import me.aquitano.health.application.metric.common.readFilters
import me.aquitano.health.application.metric.common.toResponse
import me.aquitano.health.application.metric.sleep.repository.CanonicalSleepSummaryDerivationRepository
import me.aquitano.health.infrastructure.database.suspendDbTransaction
import org.jetbrains.exposed.v1.jdbc.Database

class SleepSummaryReadService(
    private val database: Database,
    private val canonicalRepository: CanonicalSleepSummaryDerivationRepository,
) {
    suspend fun list(params: QueryParams): SleepSummariesResponse =
        suspendDbTransaction(db = database) {
            val filters = params.readFilters(
                sortSpec = QueryParamSpecs.sortByEndAt,
                latestSupported = true,
            )
            val (rows, sourceMetadata) = canonicalRepository.listCanonicalSleepSummaries(filters)
            val page = rows.keysetPage(
                limit = filters.limit,
                sort = filters.sort,
                order = filters.order,
                sortValue = { it.endAt.toString() },
                id = { it.id.toLong() },
            )
            SleepSummariesResponse(
                items = page.items.map { it.toResponse(sourceMetadata) },
                meta = page.items.meta(filters, page.nextCursor),
            )
        }
}
