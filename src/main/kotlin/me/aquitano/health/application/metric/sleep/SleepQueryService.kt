package me.aquitano.health.application.metric.sleep

import me.aquitano.health.api.dto.SleepNightsResponse
import me.aquitano.health.api.dto.SleepSessionsResponse
import me.aquitano.health.application.metric.common.QueryParams
import me.aquitano.health.application.metric.common.SortFields
import me.aquitano.health.application.metric.common.dailyReadFilters
import me.aquitano.health.application.metric.common.pagedRead
import me.aquitano.health.application.metric.common.readFilters
import me.aquitano.health.application.metric.common.toResponse
import me.aquitano.health.application.metric.sleep.repository.CanonicalSleepSessionDerivationRepository
import me.aquitano.health.application.metric.sleep.repository.SleepRepository
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Instant

class SleepQueryService(
    private val database: Database,
    private val sleepRepository: SleepRepository,
    private val canonicalSessionRepository: CanonicalSleepSessionDerivationRepository,
) {
    suspend fun listSleepSessions(params: QueryParams): SleepSessionsResponse {
        val filters = params.readFilters()
        return pagedRead(database, filters, SortFields.START_AT, { it.startAt }, { it.id.toLong() }, ::SleepSessionsResponse) {
            val (sessions, sourceMetadata) = canonicalSessionRepository.listCanonicalSleepSessions(filters)
            val stagesBySession = sleepRepository.stagesForSessions(sessions.map { it.id })
            sessions.map { it.toResponse(stagesBySession, sourceMetadata) }
        }
    }

    suspend fun listSleepNights(
        params: QueryParams,
        now: Instant,
    ): SleepNightsResponse {
        params.rejectLatest()
        val filters = params.dailyReadFilters(now, params.timezone())
        return pagedRead(database, filters, SortFields.DATE, { it.date }, { it.session.id.toLong() }, ::SleepNightsResponse) {
            val (nights, stagesBySession, sourceMetadata) = sleepRepository.listCanonicalSleepNights(filters)
            nights.map { it.toResponse(stagesBySession, sourceMetadata) }
        }
    }
}
