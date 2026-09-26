package me.aquitano.health.infrastructure.repositories

import me.aquitano.health.application.DerivedRebuildRequest
import me.aquitano.health.domain.DerivedKind
import me.aquitano.health.infrastructure.database.suspendDbTransaction
import me.aquitano.health.infrastructure.database.tables.PendingDerivedRebuildsTable
import me.aquitano.health.infrastructure.database.toDbTimestamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class PendingDerivedRebuildRecord(
    val id: Int,
    val revision: String,
    val sourceInstanceId: Int,
    val derivedKind: DerivedKind,
    val affectedDate: LocalDate,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val lastErrorMessage: String?,
)

/**
 * Durable derived work recorded together with metric writes. Rows are
 * retried by [me.aquitano.health.application.PendingDerivedRebuildSweeper] and deleted
 * once the rebuild succeeds.
 */
class PendingDerivedRebuildRepository(private val database: Database) {
    /**
     * Enqueue with the metric writes. Each revision identifies exactly the work observed by
     * a worker, so its completion cannot erase a newer ingestion for the same source and date.
     */
    fun enqueueInTransaction(request: DerivedRebuildRequest, error: String? = null, now: Instant): List<PendingDerivedRebuildRecord> {
        val queued = mutableListOf<PendingDerivedRebuildRecord>()
        val nowTimestamp = now.toDbTimestamp()
        request.affectedDates.forEach { (kind, dates) ->
            dates.forEach { date ->
                PendingDerivedRebuildsTable.upsert(
                    PendingDerivedRebuildsTable.sourceInstanceId,
                    PendingDerivedRebuildsTable.derivedKind,
                    PendingDerivedRebuildsTable.affectedDate,
                    onUpdateExclude = listOf(
                        PendingDerivedRebuildsTable.attempts,
                        PendingDerivedRebuildsTable.nextAttemptAt,
                        PendingDerivedRebuildsTable.createdAt,
                    ),
                ) {
                    it[sourceInstanceId] = request.sourceInstanceId
                    it[derivedKind] = kind.name
                    it[affectedDate] = date
                    it[attempts] = 0
                    it[nextAttemptAt] = nowTimestamp
                    it[lastErrorMessage] = error?.take(2000)
                    it[revision] = UUID.randomUUID().toString()
                    it[createdAt] = nowTimestamp
                    it[updatedAt] = nowTimestamp
                }
                queued += PendingDerivedRebuildsTable.selectAll().where {
                    (PendingDerivedRebuildsTable.sourceInstanceId eq request.sourceInstanceId) and
                        (PendingDerivedRebuildsTable.derivedKind eq kind.name) and
                        (PendingDerivedRebuildsTable.affectedDate eq date)
                }.single().toRecord()
            }
        }
        return queued
    }

    suspend fun due(now: Instant, limit: Int): List<PendingDerivedRebuildRecord> =
        suspendDbTransaction(db = database) {
            PendingDerivedRebuildsTable
                .selectAll()
                .where { PendingDerivedRebuildsTable.nextAttemptAt lessEq now.toDbTimestamp() }
                .orderBy(PendingDerivedRebuildsTable.nextAttemptAt to SortOrder.ASC)
                .limit(limit)
                .map { it.toRecord() }
        }

    suspend fun deleteCompleted(records: List<PendingDerivedRebuildRecord>) {
        if (records.isEmpty()) return
        suspendDbTransaction(db = database) {
            records.forEach { record ->
                PendingDerivedRebuildsTable.deleteWhere {
                    (PendingDerivedRebuildsTable.id eq record.id) and
                        (PendingDerivedRebuildsTable.revision eq record.revision)
                }
            }
        }
    }

    suspend fun markAttemptFailed(
        records: List<PendingDerivedRebuildRecord>,
        nextAttemptAt: (attempts: Int) -> Instant,
        error: String,
        now: Instant,
    ) {
        if (records.isEmpty()) return
        suspendDbTransaction(db = database) {
            val nowTimestamp = now.toDbTimestamp()
            records.forEach { record ->
                val attempts = record.attempts + 1
                PendingDerivedRebuildsTable.update({
                    (PendingDerivedRebuildsTable.id eq record.id) and
                        (PendingDerivedRebuildsTable.revision eq record.revision)
                }) {
                    it[this.attempts] = attempts
                    it[this.nextAttemptAt] = nextAttemptAt(attempts).toDbTimestamp()
                    it[lastErrorMessage] = error.take(2000)
                    it[updatedAt] = nowTimestamp
                }
            }
        }
    }

    private fun ResultRow.toRecord() = PendingDerivedRebuildRecord(
        id = this[PendingDerivedRebuildsTable.id].value,
        revision = this[PendingDerivedRebuildsTable.revision],
        sourceInstanceId = this[PendingDerivedRebuildsTable.sourceInstanceId],
        derivedKind = DerivedKind.valueOf(this[PendingDerivedRebuildsTable.derivedKind]),
        affectedDate = this[PendingDerivedRebuildsTable.affectedDate],
        attempts = this[PendingDerivedRebuildsTable.attempts],
        nextAttemptAt = this[PendingDerivedRebuildsTable.nextAttemptAt].toInstant(),
        lastErrorMessage = this[PendingDerivedRebuildsTable.lastErrorMessage],
    )
}
