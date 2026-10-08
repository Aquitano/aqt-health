package me.aquitano.health.infrastructure.repositories

import me.aquitano.health.domain.BatchStatus
import me.aquitano.health.domain.HealthRecord
import me.aquitano.health.domain.IngestionSnapshot
import me.aquitano.health.domain.NewIngestionRecord
import me.aquitano.health.domain.RequestValidationException
import me.aquitano.health.domain.ValidationIssue
import me.aquitano.health.domain.ValidationIssueCodes
import me.aquitano.health.infrastructure.database.tables.IngestionBatchesTable
import me.aquitano.health.infrastructure.database.tables.IngestionRecordsTable
import me.aquitano.health.infrastructure.database.tables.SourceInstancesTable
import me.aquitano.health.infrastructure.database.tables.SourcesTable
import me.aquitano.health.infrastructure.database.toApiString
import me.aquitano.health.infrastructure.database.toDbTimestamp
import me.aquitano.health.shared.Cursor
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.Instant
import java.time.ZoneOffset

data class ExistingBatch(
    val id: Int,
    val status: BatchStatus,
    val batchExternalId: String?,
)

data class IngestionRecordRef(
    val id: Int,
    val record: HealthRecord,
)

data class AdminBatchRow(
    val id: Int,
    val provider: String,
    val providerInstanceId: String,
    val batchExternalId: String?,
    val status: BatchStatus,
    val ingestedAt: String,
    val receivedAt: String,
    val processedAt: String?,
    val errorMessage: String?,
    val recordCount: Int,
)

data class AdminBatchDetailRow(
    val id: Int,
    val provider: String,
    val providerInstanceId: String,
    val batchExternalId: String?,
    val status: BatchStatus,
    val ingestedAt: String,
    val receivedAt: String,
    val processedAt: String?,
    val errorMessage: String?,
    val sourcePayloadJson: String,
)

data class AdminIngestionRecordRow(
    val id: Int,
    val recordType: String,
    val providerRecordId: String?,
    val normalizedRecordJson: String,
    val recordStartAt: String?,
    val recordEndAt: String?,
    val createdAt: String,
)

data class ReplayRecordRow(
    val id: Int,
    val recordType: String,
    val provider: String,
    val sourceInstanceId: Int,
    val normalizedRecordJson: String,
    val recordStartAt: Instant,
    val recordEndAt: Instant?,
)

class IngestionRepository {
    fun findBatchByExternalId(
        sourceInstanceId: Int,
        batchExternalId: String,
    ): ExistingBatch? =
        IngestionBatchesTable
            .selectAll()
            .where {
                (IngestionBatchesTable.sourceInstanceId eq sourceInstanceId) and
                    (IngestionBatchesTable.batchExternalId eq batchExternalId)
            }.limit(1)
            .map(::toExistingBatch)
            .singleOrNull()

    /** The latest processed batch for the window, when its snapshot still matches [contentHash]. */
    fun reusableSyncBatchId(
        sourceInstanceId: Int,
        windowKey: String,
        contentHash: String,
    ): Int? =
        IngestionBatchesTable
            .select(IngestionBatchesTable.id, IngestionBatchesTable.syncContentHash)
            .where {
                (IngestionBatchesTable.sourceInstanceId eq sourceInstanceId) and
                    (IngestionBatchesTable.syncWindowKey eq windowKey) and
                    (IngestionBatchesTable.status eq BatchStatus.Processed)
            }.orderBy(IngestionBatchesTable.id to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.takeIf { it[IngestionBatchesTable.syncContentHash] == contentHash }
            ?.let { it[IngestionBatchesTable.id].value }
            ?.takeUnless { batchId -> snapshotHasNewerValues(sourceInstanceId, batchId) }

    // A record can move to another sync window and later return with its original content.
    // A matching window hash is reusable only while its identified records still have those values.
    private fun snapshotHasNewerValues(
        sourceInstanceId: Int,
        batchId: Int,
    ): Boolean =
        requireNotNull(
            TransactionManager.current().exec(
                """
                SELECT EXISTS (
                    SELECT 1 FROM ingestion_records snapshot
                    JOIN LATERAL (
                        SELECT newer.normalized_record_json
                        FROM ingestion_records newer
                        JOIN ingestion_batches batch ON batch.id = newer.batch_id
                        WHERE newer.provider_record_id = snapshot.provider_record_id
                          AND newer.record_type = snapshot.record_type
                          AND newer.id > snapshot.id
                          AND batch.source_instance_id = $sourceInstanceId
                          AND batch.status = 'processed'
                          AND ${sameScalarIdentity("newer", "snapshot")}
                        ORDER BY newer.id DESC LIMIT 1
                    ) latest ON TRUE
                    WHERE snapshot.batch_id = $batchId
                      AND snapshot.provider_record_id IS NOT NULL
                      AND CASE WHEN snapshot.record_type = 'scalar'
                          THEN snapshot.normalized_record_json - 'unit' - 'context' - 'segment'
                          ELSE snapshot.normalized_record_json END
                          IS DISTINCT FROM
                          CASE WHEN snapshot.record_type = 'scalar'
                          THEN latest.normalized_record_json - 'unit' - 'context' - 'segment'
                          ELSE latest.normalized_record_json END
                )
                """.trimIndent(),
            ) { rows ->
                rows.next()
                rows.getBoolean(1)
            },
        )

    fun insertBatch(
        sourceInstanceId: Int,
        batchExternalId: String?,
        sourcePayloadJson: String,
        ingestedAt: Instant,
        receivedAt: Instant,
        snapshot: IngestionSnapshot? = null,
    ): Int =
        IngestionBatchesTable
            .insertAndGetId {
                it[this.sourceInstanceId] = sourceInstanceId
                it[this.batchExternalId] = batchExternalId
                it[syncWindowKey] = snapshot?.windowKey
                it[syncContentHash] = snapshot?.contentHash
                it[this.sourcePayloadJson] = sourcePayloadJson
                it[status] = BatchStatus.Received
                it[this.ingestedAt] = ingestedAt.toDbTimestamp()
                it[this.receivedAt] = receivedAt.toDbTimestamp()
                it[processedAt] = null
                it[errorMessage] = null
                it[createdAt] = receivedAt.toDbTimestamp()
                it[updatedAt] = receivedAt.toDbTimestamp()
            }.value

    fun releaseFailedBatchExternalId(
        batchId: Int,
        batchExternalId: String,
        releasedAt: Instant,
    ) {
        IngestionBatchesTable.update({
            (IngestionBatchesTable.id eq batchId) and
                (IngestionBatchesTable.status eq BatchStatus.Failed)
        }) {
            it[this.batchExternalId] = "$batchExternalId#failed:$batchId"
            it[updatedAt] = releasedAt.toDbTimestamp()
        }
    }

    fun insertRecords(
        batchId: Int,
        records: List<NewIngestionRecord>,
        now: Instant,
    ): List<IngestionRecordRef> =
        records.chunked(INSERT_CHUNK_SIZE).flatMap { chunk ->
            val rows =
                IngestionRecordsTable.batchInsert(chunk) { (record, normalizedJson) ->
                    this[IngestionRecordsTable.batchId] = batchId
                    this[IngestionRecordsTable.recordType] = record.recordType
                    this[IngestionRecordsTable.providerRecordId] = record.providerRecordId
                    this[IngestionRecordsTable.normalizedRecordJson] = normalizedJson
                    this[IngestionRecordsTable.recordStartAt] = record.recordStartAt?.toDbTimestamp()
                    this[IngestionRecordsTable.recordEndAt] = record.recordEndAt?.toDbTimestamp()
                    this[IngestionRecordsTable.createdAt] = now.toDbTimestamp()
                }
            chunk.zip(rows) { inserted, row ->
                IngestionRecordRef(id = row[IngestionRecordsTable.id].value, record = inserted.record)
            }
        }

    fun markProcessed(
        batchId: Int,
        processedAt: Instant,
    ) {
        IngestionBatchesTable.update({ IngestionBatchesTable.id eq batchId }) {
            it[status] = BatchStatus.Processed
            it[this.processedAt] = processedAt.toDbTimestamp()
            it[updatedAt] = processedAt.toDbTimestamp()
            it[errorMessage] = null
        }
    }

    fun markFailed(
        batchId: Int,
        failedAt: Instant,
        error: String,
    ) {
        IngestionBatchesTable.update({ IngestionBatchesTable.id eq batchId }) {
            it[status] = BatchStatus.Failed
            it[processedAt] = null
            it[updatedAt] = failedAt.toDbTimestamp()
            it[errorMessage] = error.take(2000)
        }
    }

    fun markDerivedRebuildFailed(
        batchId: Int,
        failedAt: Instant,
        error: String,
    ) {
        IngestionBatchesTable.update({ IngestionBatchesTable.id eq batchId }) {
            it[updatedAt] = failedAt.toDbTimestamp()
            it[errorMessage] = "Derived rebuild failed: ${error.take(1976)}"
        }
    }

    fun listBatches(
        status: BatchStatus?,
        from: Instant?,
        to: Instant?,
        limit: Int,
        cursor: Cursor? = null,
    ): List<AdminBatchRow> {
        val conditions =
            listOfNotNull(
                status?.let { IngestionBatchesTable.status eq it },
                from?.let { IngestionBatchesTable.receivedAt greaterEq it.toDbTimestamp() },
                to?.let { IngestionBatchesTable.receivedAt less it.toDbTimestamp() },
                cursor?.let(::receivedAtKeyset),
            )

        val batches =
            IngestionBatchesTable
                .innerJoin(SourceInstancesTable)
                .innerJoin(SourcesTable)
                .selectAll()
                .where(combineConditions(conditions))
                .orderBy(
                    IngestionBatchesTable.receivedAt to SortOrder.DESC,
                    IngestionBatchesTable.id to SortOrder.DESC,
                ).limit(limit)
                .toList()

        val recordCounts =
            recordCounts(batches.map { it[IngestionBatchesTable.id].value })
        return batches.map {
            AdminBatchRow(
                id = it[IngestionBatchesTable.id].value,
                provider = it[SourcesTable.code],
                providerInstanceId = it[SourceInstancesTable.providerInstanceId],
                batchExternalId = it[IngestionBatchesTable.batchExternalId],
                status = it[IngestionBatchesTable.status],
                ingestedAt = it[IngestionBatchesTable.ingestedAt].toApiString(),
                receivedAt = it[IngestionBatchesTable.receivedAt].toApiString(),
                processedAt = it[IngestionBatchesTable.processedAt]?.toApiString(),
                errorMessage = it[IngestionBatchesTable.errorMessage],
                recordCount =
                    recordCounts[it[IngestionBatchesTable.id].value]
                        ?: 0,
            )
        }
    }

    private fun receivedAtKeyset(cursor: Cursor): Op<Boolean> {
        val receivedAt =
            runCatching {
                Instant.parse(cursor.sortValue).atOffset(ZoneOffset.UTC)
            }.getOrElse {
                throw RequestValidationException(field = "cursor", code = ValidationIssueCodes.InvalidFormat, message = "is not a valid cursor")
            }
        val sortValue = LiteralOp(IngestionBatchesTable.receivedAt.columnType, receivedAt)
        val idValue = intParam(cursor.lastId.toInt())
        return LessOp(IngestionBatchesTable.receivedAt, sortValue) or
            (EqOp(IngestionBatchesTable.receivedAt, sortValue) and LessOp(IngestionBatchesTable.id, idValue))
    }

    fun findBatchDetail(batchId: Int): AdminBatchDetailRow? =
        IngestionBatchesTable
            .innerJoin(SourceInstancesTable)
            .innerJoin(SourcesTable)
            .selectAll()
            .where { IngestionBatchesTable.id eq batchId }
            .limit(1)
            .map {
                AdminBatchDetailRow(
                    id = it[IngestionBatchesTable.id].value,
                    provider = it[SourcesTable.code],
                    providerInstanceId = it[SourceInstancesTable.providerInstanceId],
                    batchExternalId = it[IngestionBatchesTable.batchExternalId],
                    status = it[IngestionBatchesTable.status],
                    ingestedAt = it[IngestionBatchesTable.ingestedAt].toApiString(),
                    receivedAt = it[IngestionBatchesTable.receivedAt].toApiString(),
                    processedAt = it[IngestionBatchesTable.processedAt]?.toApiString(),
                    errorMessage = it[IngestionBatchesTable.errorMessage],
                    sourcePayloadJson = it[IngestionBatchesTable.sourcePayloadJson],
                )
            }.singleOrNull()

    fun listRecordsForBatch(batchId: Int): List<AdminIngestionRecordRow> =
        IngestionRecordsTable
            .selectAll()
            .where { IngestionRecordsTable.batchId eq batchId }
            .orderBy(IngestionRecordsTable.id to SortOrder.ASC)
            .map {
                AdminIngestionRecordRow(
                    id = it[IngestionRecordsTable.id].value,
                    recordType = it[IngestionRecordsTable.recordType],
                    providerRecordId = it[IngestionRecordsTable.providerRecordId],
                    normalizedRecordJson = it[IngestionRecordsTable.normalizedRecordJson],
                    recordStartAt = it[IngestionRecordsTable.recordStartAt]?.toApiString(),
                    recordEndAt = it[IngestionRecordsTable.recordEndAt]?.toApiString(),
                    createdAt = it[IngestionRecordsTable.createdAt].toApiString(),
                )
            }

    /**
     * Bounds of record_start_at across processed batches, used to plan replay day items.
     * Records without a record_start_at are not replayable by date and are excluded.
     */
    fun replayDateBounds(recordTypes: Set<String>?): Pair<Instant, Instant>? {
        val minStart = IngestionRecordsTable.recordStartAt.min()
        val maxStart = IngestionRecordsTable.recordStartAt.max()
        val conditions =
            listOfNotNull(
                IngestionBatchesTable.status eq BatchStatus.Processed,
                recordTypes?.let { IngestionRecordsTable.recordType inList it },
            )
        return IngestionRecordsTable
            .innerJoin(IngestionBatchesTable)
            .select(minStart, maxStart)
            .where(combineConditions(conditions))
            .firstOrNull()
            ?.let { row ->
                val min = row[minStart]?.toInstant() ?: return null
                val max = row[maxStart]?.toInstant() ?: return null
                min to max
            }
    }

    /** Records of processed batches whose record_start_at falls in [dayStart, dayEnd). */
    fun listRecordsForReplay(
        dayStart: Instant,
        dayEnd: Instant,
        recordTypes: Set<String>?,
    ): List<ReplayRecordRow> =
        IngestionRecordsTable
            .innerJoin(IngestionBatchesTable)
            .innerJoin(SourceInstancesTable)
            .innerJoin(SourcesTable)
            .selectAll()
            .where(replayConditions(dayStart, dayEnd, recordTypes))
            .orderBy(IngestionRecordsTable.id to SortOrder.ASC)
            .mapNotNull { row ->
                row[IngestionRecordsTable.recordStartAt]?.let { recordStartAt ->
                    ReplayRecordRow(
                        id = row[IngestionRecordsTable.id].value,
                        recordType = row[IngestionRecordsTable.recordType],
                        provider = row[SourcesTable.code],
                        sourceInstanceId = row[IngestionBatchesTable.sourceInstanceId],
                        normalizedRecordJson = row[IngestionRecordsTable.normalizedRecordJson],
                        recordStartAt = recordStartAt.toInstant(),
                        recordEndAt = row[IngestionRecordsTable.recordEndAt]?.toInstant(),
                    )
                }
            }

    /** Compare the prepared immutable log snapshot while ingestion writes are locked. */
    fun recordIdsForReplay(
        dayStart: Instant,
        dayEnd: Instant,
        recordTypes: Set<String>?,
    ): Set<Int> =
        IngestionRecordsTable
            .innerJoin(IngestionBatchesTable)
            .select(IngestionRecordsTable.id)
            .where(replayConditions(dayStart, dayEnd, recordTypes))
            .mapTo(hashSetOf()) { it[IngestionRecordsTable.id].value }

    private fun replayConditions(
        dayStart: Instant,
        dayEnd: Instant,
        recordTypes: Set<String>?,
    ): Op<Boolean> {
        val conditions =
            listOfNotNull(
                IngestionBatchesTable.status eq BatchStatus.Processed,
                IngestionRecordsTable.recordStartAt greaterEq dayStart.toDbTimestamp(),
                IngestionRecordsTable.recordStartAt less dayEnd.toDbTimestamp(),
                recordTypes?.let { IngestionRecordsTable.recordType inList it },
            )
        return combineConditions(conditions)
    }

    private fun recordCounts(batchIds: List<Int>): Map<Int, Int> {
        if (batchIds.isEmpty()) return emptyMap()
        val countExpression = IngestionRecordsTable.id.count()
        return IngestionRecordsTable
            .select(IngestionRecordsTable.batchId, countExpression)
            .where { IngestionRecordsTable.batchId inList batchIds }
            .groupBy(IngestionRecordsTable.batchId)
            .associate { it[IngestionRecordsTable.batchId] to it[countExpression].toInt() }
    }

    private fun combineConditions(conditions: List<Op<Boolean>>): Op<Boolean> = conditions.reduceOrNull { left, right -> left and right } ?: Op.TRUE

    private fun toExistingBatch(row: ResultRow): ExistingBatch =
        ExistingBatch(
            id = row[IngestionBatchesTable.id].value,
            status = row[IngestionBatchesTable.status],
            batchExternalId = row[IngestionBatchesTable.batchExternalId],
        )
}

/** Rows per multi-row INSERT; keeps statements bounded while avoiding per-row round trips. */
internal const val INSERT_CHUNK_SIZE = 1_000
