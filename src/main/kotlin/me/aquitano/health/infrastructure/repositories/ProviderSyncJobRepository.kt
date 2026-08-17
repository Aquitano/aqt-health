package me.aquitano.health.infrastructure.repositories

import me.aquitano.health.infrastructure.database.tables.ProviderSyncJobsTable
import me.aquitano.health.infrastructure.database.toDbTimestamp
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import me.aquitano.health.infrastructure.database.suspendDbTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

data class ProviderSyncJobRecord(
    val id: String,
    val providerCode: String,
    val idempotencyRequestHash: String?,
    val providerInstanceId: String?,
    val requestedFrom: Instant,
    val requestedTo: Instant,
    val dataTypes: List<String>?,
    val pageSize: Int?,
    val status: String,
    val totalItems: Int,
    val completedItems: Int,
    val currentDataType: String?,
    val currentFrom: Instant?,
    val currentTo: Instant?,
    val lastCompletedDataType: String?,
    val lastCompletedFrom: Instant?,
    val lastCompletedTo: Instant?,
    val batchesCount: Int,
    val emptyCount: Int,
    val errorCount: Int,
    val restartCount: Int,
    val summaryJson: String?,
    val errorMessage: String?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val updatedAt: Instant,
    val finishedAt: Instant?,
)

data class ProviderSyncJobCreateResult(val record: ProviderSyncJobRecord, val created: Boolean)

data class ProviderSyncJobRequeueResult(
    val resumed: List<ProviderSyncJobRecord>,
    val abandoned: List<ProviderSyncJobRecord>,
)

class ProviderSyncJobRepository(private val database: Database) {
    suspend fun create(
        id: String,
        providerCode: String,
        providerInstanceId: String?,
        requestedFrom: Instant,
        requestedTo: Instant,
        dataTypes: List<String>?,
        pageSize: Int?,
        now: Instant,
        idempotencyKey: String? = null,
        idempotencyRequestHash: String? = null,
    ): ProviderSyncJobCreateResult =
        suspendDbTransaction(db = database) {
            // insertIgnore behaves like a plain insert when no unique-key conflict exists,
            // which is always the case for the non-idempotent path (fresh id, null key).
            val inserted = ProviderSyncJobsTable.insertIgnore {
                it[this.id] = id
                it[this.providerCode] = providerCode
                it[this.idempotencyKey] = idempotencyKey
                it[this.idempotencyRequestHash] = idempotencyRequestHash
                it[this.providerInstanceId] = providerInstanceId
                it[this.requestedFrom] = requestedFrom.toDbTimestamp()
                it[this.requestedTo] = requestedTo.toDbTimestamp()
                it[this.dataTypes] = dataTypes?.let(::encodeDataTypes)
                it[this.pageSize] = pageSize
                it[status] = "queued"
                it[totalItems] = 0
                it[completedItems] = 0
                it[batchesCount] = 0
                it[emptyCount] = 0
                it[errorCount] = 0
                it[restartCount] = 0
                it[createdAt] = now.toDbTimestamp()
                it[updatedAt] = now.toDbTimestamp()
            }.insertedCount > 0
            val record = getByIdInTransaction(id)
                ?: findByIdempotencyKeyInTransaction(providerCode, idempotencyKey!!)!!
            ProviderSyncJobCreateResult(record, created = inserted)
        }

    suspend fun get(id: String): ProviderSyncJobRecord? =
        suspendDbTransaction(db = database) { getByIdInTransaction(id) }

    suspend fun findByIdempotencyKey(
        providerCode: String,
        idempotencyKey: String,
    ): ProviderSyncJobRecord? =
        suspendDbTransaction(db = database) {
            findByIdempotencyKeyInTransaction(providerCode, idempotencyKey)
        }

    suspend fun latest(providerCode: String? = null): ProviderSyncJobRecord? =
        suspendDbTransaction(db = database) {
            ProviderSyncJobsTable
                .selectAll()
                .let { query ->
                    providerCode?.let {
                        query.where { ProviderSyncJobsTable.providerCode eq it }
                    } ?: query
                }
                .orderBy(ProviderSyncJobsTable.createdAt to SortOrder.DESC)
                .limit(1)
                .map { it.toRecord() }
                .singleOrNull()
        }

    suspend fun markRunning(id: String, now: Instant) {
        suspendDbTransaction(db = database) {
            ProviderSyncJobsTable.update({ ProviderSyncJobsTable.id eq id }) {
                it[status] = "running"
                it[startedAt] = now.toDbTimestamp()
                it[updatedAt] = now.toDbTimestamp()
                it[errorMessage] = null
            }
        }
    }

    suspend fun markStarted(
        id: String,
        providerInstanceId: String,
        totalItems: Int,
        now: Instant,
    ) {
        suspendDbTransaction(db = database) {
            ProviderSyncJobsTable.update({ ProviderSyncJobsTable.id eq id }) {
                it[this.providerInstanceId] = providerInstanceId
                it[this.totalItems] = totalItems
                it[updatedAt] = now.toDbTimestamp()
            }
        }
    }

    suspend fun markItemStarted(
        id: String,
        dataType: String,
        from: Instant,
        to: Instant,
        now: Instant,
    ) {
        suspendDbTransaction(db = database) {
            ProviderSyncJobsTable.update({ ProviderSyncJobsTable.id eq id }) {
                it[currentDataType] = dataType
                it[currentFrom] = from.toDbTimestamp()
                it[currentTo] = to.toDbTimestamp()
                it[updatedAt] = now.toDbTimestamp()
            }
        }
    }

    suspend fun markItemCompleted(
        id: String,
        dataType: String,
        from: Instant,
        to: Instant,
        now: Instant,
    ) {
        suspendDbTransaction(db = database) {
            val existing = getByIdInTransaction(id) ?: return@suspendDbTransaction
            ProviderSyncJobsTable.update({ ProviderSyncJobsTable.id eq id }) {
                it[completedItems] = existing.completedItems + 1
                it[lastCompletedDataType] = dataType
                it[lastCompletedFrom] = from.toDbTimestamp()
                it[lastCompletedTo] = to.toDbTimestamp()
                it[updatedAt] = now.toDbTimestamp()
            }
        }
    }

    suspend fun finish(
        id: String,
        status: String,
        batchesCount: Int,
        emptyCount: Int,
        errorCount: Int,
        summaryJson: String?,
        errorMessage: String?,
        now: Instant,
    ) {
        suspendDbTransaction(db = database) {
            ProviderSyncJobsTable.update({ ProviderSyncJobsTable.id eq id }) {
                it[this.status] = status
                it[this.batchesCount] = batchesCount
                it[this.emptyCount] = emptyCount
                it[this.errorCount] = errorCount
                it[this.summaryJson] = summaryJson
                it[this.errorMessage] = errorMessage?.take(2000)
                it[currentDataType] = null
                it[currentFrom] = null
                it[currentTo] = null
                it[updatedAt] = now.toDbTimestamp()
                it[finishedAt] = now.toDbTimestamp()
            }
        }
    }

    /**
     * Requeues jobs interrupted by a restart so the service can relaunch them, and fails
     * jobs that have already been restarted [maxRestarts] times to stop crash loops.
     */
    suspend fun requeueInterruptedJobs(now: Instant, maxRestarts: Int): ProviderSyncJobRequeueResult =
        suspendDbTransaction(db = database) {
            val interrupted = ProviderSyncJobsTable
                .selectAll()
                .where { ProviderSyncJobsTable.status inList listOf("queued", "running") }
                .map { it.toRecord() }
            val (abandoned, resumable) = interrupted.partition { it.restartCount >= maxRestarts }

            abandoned.forEach { job ->
                ProviderSyncJobsTable.update({ ProviderSyncJobsTable.id eq job.id }) {
                    it[status] = "failed"
                    it[errorMessage] =
                        "Backend restarted $maxRestarts times while this job was unfinished; not resuming again. Start a new sync to resume from completed chunks."
                    it[updatedAt] = now.toDbTimestamp()
                    it[finishedAt] = now.toDbTimestamp()
                }
            }
            resumable.forEach { job ->
                ProviderSyncJobsTable.update({ ProviderSyncJobsTable.id eq job.id }) {
                    it[status] = "queued"
                    it[restartCount] = job.restartCount + 1
                    // The relaunch reruns the full request, so completed progress starts over.
                    it[completedItems] = 0
                    it[currentDataType] = null
                    it[currentFrom] = null
                    it[currentTo] = null
                    it[errorMessage] = null
                    it[updatedAt] = now.toDbTimestamp()
                }
            }

            ProviderSyncJobRequeueResult(
                resumed = resumable.map { it.copy(status = "queued", restartCount = it.restartCount + 1, completedItems = 0) },
                abandoned = abandoned,
            )
        }

    private fun getByIdInTransaction(id: String): ProviderSyncJobRecord? =
        ProviderSyncJobsTable
            .selectAll()
            .where { ProviderSyncJobsTable.id eq id }
            .limit(1)
            .map { it.toRecord() }
            .singleOrNull()

    private fun findByIdempotencyKeyInTransaction(
        providerCode: String,
        idempotencyKey: String,
    ): ProviderSyncJobRecord? =
        ProviderSyncJobsTable
            .selectAll()
            .where {
                (ProviderSyncJobsTable.providerCode eq providerCode) and
                    (ProviderSyncJobsTable.idempotencyKey eq idempotencyKey)
            }
            .limit(1)
            .map { it.toRecord() }
            .singleOrNull()

    private fun ResultRow.toRecord(): ProviderSyncJobRecord =
        ProviderSyncJobRecord(
            id = this[ProviderSyncJobsTable.id],
            providerCode = this[ProviderSyncJobsTable.providerCode],
            idempotencyRequestHash = this[ProviderSyncJobsTable.idempotencyRequestHash],
            providerInstanceId = this[ProviderSyncJobsTable.providerInstanceId],
            requestedFrom = this[ProviderSyncJobsTable.requestedFrom].toInstant(),
            requestedTo = this[ProviderSyncJobsTable.requestedTo].toInstant(),
            dataTypes = this[ProviderSyncJobsTable.dataTypes]?.let(::decodeDataTypes),
            pageSize = this[ProviderSyncJobsTable.pageSize],
            status = this[ProviderSyncJobsTable.status],
            totalItems = this[ProviderSyncJobsTable.totalItems],
            completedItems = this[ProviderSyncJobsTable.completedItems],
            currentDataType = this[ProviderSyncJobsTable.currentDataType],
            currentFrom = this[ProviderSyncJobsTable.currentFrom]?.toInstant(),
            currentTo = this[ProviderSyncJobsTable.currentTo]?.toInstant(),
            lastCompletedDataType = this[ProviderSyncJobsTable.lastCompletedDataType],
            lastCompletedFrom = this[ProviderSyncJobsTable.lastCompletedFrom]?.toInstant(),
            lastCompletedTo = this[ProviderSyncJobsTable.lastCompletedTo]?.toInstant(),
            batchesCount = this[ProviderSyncJobsTable.batchesCount],
            emptyCount = this[ProviderSyncJobsTable.emptyCount],
            errorCount = this[ProviderSyncJobsTable.errorCount],
            restartCount = this[ProviderSyncJobsTable.restartCount],
            summaryJson = this[ProviderSyncJobsTable.summaryJson],
            errorMessage = this[ProviderSyncJobsTable.errorMessage],
            createdAt = this[ProviderSyncJobsTable.createdAt].toInstant(),
            startedAt = this[ProviderSyncJobsTable.startedAt]?.toInstant(),
            updatedAt = this[ProviderSyncJobsTable.updatedAt].toInstant(),
            finishedAt = this[ProviderSyncJobsTable.finishedAt]?.toInstant(),
        )

}

private fun encodeDataTypes(dataTypes: List<String>): String =
    dataTypes.joinToString(",")

private fun decodeDataTypes(value: String): List<String> =
    value.split(",").filter { it.isNotBlank() }
