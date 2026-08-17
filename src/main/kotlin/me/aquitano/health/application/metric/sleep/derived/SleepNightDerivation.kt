package me.aquitano.health.application.metric.sleep.derived

import me.aquitano.health.application.metric.sleep.repository.SleepNightDerivationRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

const val SLEEP_NIGHT_ALGORITHM_VERSION = 1

class SleepNightDerivation(
    private val repository: SleepNightDerivationRepository,
) {
    suspend fun recompute(
        sourceInstanceIds: Set<Int>?,
        dates: Set<LocalDate>,
        timezone: ZoneId,
        computedAt: Instant,
    ) {
        dates.forEach { date ->
            val windowStart = date.atStartOfDay(timezone).toInstant()
            val windowEnd = date.plusDays(1).atStartOfDay(timezone).toInstant()
            val sessions = repository.listSleepSessionsEndingInWindow(
                sourceInstanceIds = sourceInstanceIds,
                windowStart = windowStart,
                windowEnd = windowEnd,
            )
            repository.replaceSleepNights(
                SleepNightOutput(
                    date = date,
                    timezone = timezone,
                    algorithmVersion = SLEEP_NIGHT_ALGORITHM_VERSION,
                    computedAt = computedAt,
                    sourceInstanceIds = sourceInstanceIds,
                    nights = sessions.map {
                        SleepNightDerivedRow(
                            sourceInstanceId = it.sourceInstanceId,
                            sleepSessionId = it.id,
                        )
                    },
                )
            )
        }
    }
}

data class SleepNightRawSession(
    val id: Int,
    val sourceInstanceId: Int,
    val endAt: Instant,
)

data class SleepNightOutput(
    val date: LocalDate,
    val timezone: ZoneId,
    val algorithmVersion: Int,
    val computedAt: Instant,
    val sourceInstanceIds: Set<Int>?,
    val nights: List<SleepNightDerivedRow>,
)

data class SleepNightDerivedRow(
    val sourceInstanceId: Int,
    val sleepSessionId: Int,
)
