package me.aquitano.health.application

import me.aquitano.health.application.metric.steps.derived.CanonicalStepDerivationService
import me.aquitano.health.domain.RecordTypes
import me.aquitano.health.shared.utcDate
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Instant
import java.time.LocalDate

interface DerivedRebuildExecutor {
    suspend fun rebuild(
        requests: List<DerivedRebuildRequest>,
        computedAt: Instant,
    )
}

data class DerivedRebuildRequest(
    val sourceInstanceId: Int,
    val affectedStepDates: Set<LocalDate> = emptySet(),
)

fun interface DerivedRebuildAction {
    suspend fun rebuild(
        database: Database,
        sourceInstanceIds: Set<Int>,
        dates: Set<LocalDate>,
        computedAt: Instant,
    )
}

/** Maps one raw record's time span to the dates whose derived data it invalidates. */
fun interface AffectedDatesResolver {
    fun affectedDates(
        recordType: String,
        startAt: Instant,
        endAt: Instant?,
    ): Set<LocalDate>
}

class DerivedRebuildModule(
    val affectedDates: AffectedDatesResolver,
    val action: DerivedRebuildAction,
)

/** Ordered registry of derived rebuilds; order is the execution order. */
class DerivedRebuildModuleRegistry(
    val modules: List<DerivedRebuildModule>,
) {
    /**
     * The single record-to-rebuild-dates mapping shared by the ingestion write path and replay,
     * so a rebuild cannot be wired into one and silently skipped by the other.
     */
    fun affectedDatesFor(
        recordType: String,
        startAt: Instant,
        endAt: Instant?,
    ): Set<LocalDate> = modules.flatMapTo(linkedSetOf()) { it.affectedDates.affectedDates(recordType, startAt, endAt) }
}

/** The canonical post-ingestion rebuild wiring; order is the execution order. */
fun derivedRebuildModules(canonicalStepService: CanonicalStepDerivationService): List<DerivedRebuildModule> =
    listOf(
        DerivedRebuildModule(
            affectedDates = { recordType, startAt, endAt ->
                if (recordType == RecordTypes.STEP_INTERVAL) {
                    // Replay rows may carry a null end; treat them as an instant-wide interval.
                    affectedUtcDates(startAt, endAt ?: startAt.plusNanos(1))
                } else {
                    emptySet()
                }
            },
            action = { database, _, dates, computedAt -> canonicalStepService.recompute(database, dates, computedAt) },
        ),
    )

internal fun affectedUtcDates(
    start: Instant,
    exclusiveEnd: Instant,
): Set<LocalDate> {
    val lastIncludedDate = exclusiveEnd.minusNanos(1).utcDate()
    val dates = linkedSetOf<LocalDate>()
    var cursor = start.utcDate()
    while (!cursor.isAfter(lastIncludedDate)) {
        dates.add(cursor)
        cursor = cursor.plusDays(1)
    }
    return dates
}

class PerDateDerivedRebuildExecutor(
    private val database: Database,
    private val registry: DerivedRebuildModuleRegistry,
) : DerivedRebuildExecutor {
    override suspend fun rebuild(
        requests: List<DerivedRebuildRequest>,
        computedAt: Instant,
    ) {
        registry.modules.forEach { module ->
            val sourcesByDate = mutableMapOf<LocalDate, MutableSet<Int>>()
            requests.forEach { request ->
                request.affectedStepDates.forEach { date ->
                    sourcesByDate.getOrPut(date) { linkedSetOf() }.add(request.sourceInstanceId)
                }
            }
            sourcesByDate.toSortedMap().forEach { (date, sources) ->
                module.action.rebuild(database, sources, setOf(date), computedAt)
            }
        }
    }
}
