package me.aquitano.health.application.metric.activity

import me.aquitano.health.api.dto.ActivitySummariesResponse
import me.aquitano.health.application.metric.activity.repository.CanonicalActivitySummaryDerivationRepository
import me.aquitano.health.application.metric.common.QueryParams
import me.aquitano.health.application.metric.common.dailyLatestReadFilters
import me.aquitano.health.application.metric.common.dailyReadFilters
import me.aquitano.health.application.metric.common.pagedRead
import me.aquitano.health.application.metric.common.toResponse
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Instant

class ActivityQueryService(
    private val database: Database,
    private val canonicalRepository: CanonicalActivitySummaryDerivationRepository,
) {
    suspend fun listActivitySummaries(
        params: QueryParams,
        now: Instant,
    ): ActivitySummariesResponse {
        val latest = params.boolean("latest", default = false)
        val filters = if (latest) params.dailyLatestReadFilters(now) else params.dailyReadFilters(now)
        return pagedRead(database, filters, { it.date }, { it.id.toLong() }, ::ActivitySummariesResponse) {
            val (rows, sourceMetadata) = canonicalRepository.listCanonicalActivitySummaries(filters)
            rows.map { it.toResponse(sourceMetadata) }
        }.let { if (latest) it.copy(meta = it.meta.copy(nextCursor = null)) else it }
    }
}
