package me.aquitano.health.application.metric.common.repository

import me.aquitano.health.application.metric.common.MetricWrite
import me.aquitano.health.domain.RecordTypes
import me.aquitano.health.domain.ScalarMetricRegistry
import me.aquitano.health.domain.ScalarSampleRecord
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.Instant

/** A previous projection's dates must be invalidated when a correction moves the record. */
data class ReplacedRecordSpan(val recordType: String, val startAt: Instant, val endAt: Instant?)

data class PreparedMetricWrites(
    val writes: List<MetricWrite>,
    val replacedSpans: List<ReplacedRecordSpan>,
    val replacedRecordIds: Set<Int>,
)

/**
 * Coordinates corrections for all projection writers inside their transaction. The append-only
 * log determines the newest version even when replay has deleted the projection being rebuilt.
 */
class ProviderRecordCorrections {
    fun prepare(sourceInstanceId: Int, writes: List<MetricWrite>): PreparedMetricWrites {
        if (writes.isEmpty()) return PreparedMetricWrites(emptyList(), emptyList(), emptySet())
        val transaction = TransactionManager.current()
        // Ingestion inserts its raw records before taking this lock. Replay takes its raw-log
        // table lock first, then locks sources in ascending order through writeAll.
        transaction.exec("SELECT pg_advisory_xact_lock(384730, $sourceInstanceId)")
        val latest = mutableMapOf<ProviderIdentity, MetricWrite>()
        writes.forEach { write ->
            val providerId = write.record.providerRecordId ?: return@forEach
            val scalar = write.record as? ScalarSampleRecord
            val key = ProviderIdentity(
                providerId, write.record.recordType, scalar?.value?.metricType,
                scalar?.value?.context, scalar?.value?.segment,
            )
            if ((latest[key]?.ingestionRecordId ?: -1) < write.ingestionRecordId) latest[key] = write
        }
        val identified = latest.values.toList()
        if (identified.isEmpty()) return PreparedMetricWrites(writes, emptyList(), emptySet())

        val eligibleIds = hashSetOf<Int>()
        val firstVersionIds = hashMapOf<Int, Int>()
        val replacedRecordIds = hashSetOf<Int>()
        identified.chunked(CHUNK_SIZE).forEach { chunk ->
            val ids = chunk.joinToString(",") { it.ingestionRecordId.toString() }
            transaction.exec(
                """
                SELECT incoming.id, EXISTS (
                    SELECT 1 FROM ingestion_records older
                    JOIN ingestion_batches batch ON batch.id = older.batch_id
                    WHERE older.provider_record_id = incoming.provider_record_id
                      AND older.record_type = incoming.record_type
                      AND older.id < incoming.id
                      AND batch.source_instance_id = $sourceInstanceId
                      AND batch.status = 'processed'
                      AND older.normalized_record_json <> incoming.normalized_record_json
                      AND ${sameScalarIdentity("older", "incoming")}
                ) AS has_previous_version,
                COALESCE((
                    SELECT MIN(older.id) FROM ingestion_records older
                    JOIN ingestion_batches batch ON batch.id = older.batch_id
                    WHERE older.provider_record_id = incoming.provider_record_id
                      AND older.record_type = incoming.record_type
                      AND older.id < incoming.id
                      AND batch.source_instance_id = $sourceInstanceId
                      AND (batch.status = 'processed' OR older.batch_id = incoming.batch_id)
                      AND ${sameScalarIdentity("older", "incoming")}
                ), incoming.id) AS first_version_id
                FROM ingestion_records incoming
                WHERE incoming.id IN ($ids)
                  AND NOT EXISTS (
                    SELECT 1 FROM ingestion_records newer
                    JOIN ingestion_batches batch ON batch.id = newer.batch_id
                    WHERE newer.provider_record_id = incoming.provider_record_id
                      AND newer.record_type = incoming.record_type
                      AND newer.id > incoming.id
                      AND batch.source_instance_id = $sourceInstanceId
                      AND (batch.status = 'processed' OR newer.batch_id = incoming.batch_id)
                      AND ${sameScalarIdentity("newer", "incoming")}
                  )
                """.trimIndent()
            ) { rows ->
                while (rows.next()) {
                    val id = rows.getInt("id")
                    eligibleIds += id
                    firstVersionIds[id] = rows.getInt("first_version_id")
                    if (rows.getBoolean("has_previous_version")) replacedRecordIds += id
                }
            }
        }

        val replacedSpans = mutableListOf<ReplacedRecordSpan>()
        identified.filter { it.ingestionRecordId in eligibleIds }
            .groupBy { it.record.recordType }
            .forEach { (recordType, records) ->
                val projection = projectionFor(recordType)
                records.chunked(CHUNK_SIZE).forEach { chunk ->
                    val ids = chunk.joinToString(",") { it.ingestionRecordId.toString() }
                    val deleteIds = mutableListOf<Long>()
                    val scalarIdentity = if (recordType == RecordTypes.SCALAR) {
                        """AND projection.metric_type = incoming.normalized_record_json->>'metricType'
                           AND COALESCE(projection.context, '') = ${scalarContext("incoming")}
                           AND COALESCE(projection.segment, '') = COALESCE(incoming.normalized_record_json->>'segment', '')"""
                    } else ""
                    val unchanged = if (recordType == RecordTypes.SCALAR) {
                        "previous.normalized_record_json - 'unit' - 'context' - 'segment' = " +
                            "incoming.normalized_record_json - 'unit' - 'context' - 'segment'"
                    } else "previous.normalized_record_json = incoming.normalized_record_json"
                    transaction.exec(
                        """
                        SELECT projection.id, incoming.id AS incoming_id,
                               projection.ingestion_record_id,
                               projection.${projection.startColumn} AS previous_start,
                               ${projection.endColumn?.let { "projection.$it" } ?: "NULL::timestamptz"} AS previous_end,
                               $unchanged AS unchanged
                        FROM ${projection.table} projection
                        JOIN ingestion_records incoming
                          ON incoming.provider_record_id = projection.provider_record_id
                         AND incoming.id IN ($ids)
                         $scalarIdentity
                        LEFT JOIN ingestion_records previous ON previous.id = projection.ingestion_record_id
                        WHERE projection.source_instance_id = $sourceInstanceId
                        """.trimIndent()
                    ) { rows ->
                        while (rows.next()) {
                            val incomingId = rows.getInt("incoming_id")
                            if (rows.getInt("ingestion_record_id") >= incomingId || rows.getBoolean("unchanged")) {
                                eligibleIds -= incomingId
                            } else {
                                deleteIds += rows.getLong("id")
                                replacedRecordIds += incomingId
                                replacedSpans += ReplacedRecordSpan(
                                    recordType,
                                    rows.getTimestamp("previous_start").toInstant(),
                                    rows.getTimestamp("previous_end")?.toInstant(),
                                )
                            }
                        }
                    }
                    if (deleteIds.isNotEmpty()) {
                        // Reinsert through the normal writer. Child sleep stages and canonical step
                        // rows cascade, and a changed step ID invalidates prepared derived output.
                        transaction.exec("DELETE FROM ${projection.table} WHERE id IN (${deleteIds.joinToString(",")})")
                    }
                }
            }
        return PreparedMetricWrites(
            // Repeated unchanged snapshots must retain their original overlap priority during replay.
            writes.filter { it.record.providerRecordId == null || it.ingestionRecordId in eligibleIds }
                .sortedBy { firstVersionIds[it.ingestionRecordId] ?: it.ingestionRecordId },
            replacedSpans,
            replacedRecordIds,
        )
    }
}

private fun sameScalarIdentity(left: String, right: String): String =
    """($right.record_type <> 'scalar' OR (
        $left.normalized_record_json->>'metricType' = $right.normalized_record_json->>'metricType'
        AND ${scalarContext(left)} = ${scalarContext(right)}
        AND COALESCE($left.normalized_record_json->>'segment', '') = COALESCE($right.normalized_record_json->>'segment', '')
    ))"""

// Defaults mirror mapScalarSample; absent context means "unknown" for context-bearing metrics.
private val contextualMetricTypes = ScalarMetricRegistry.descriptors
    .filter { it.allowedContexts != null }
    .joinToString(",") { "'${it.metricType.replace("'", "''")}'" }

private fun scalarContext(alias: String): String =
    "COALESCE($alias.normalized_record_json->>'context', " +
        "CASE WHEN $alias.normalized_record_json->>'metricType' IN ($contextualMetricTypes) " +
        "THEN 'unknown' ELSE '' END)"

private data class ProviderIdentity(
    val providerId: String,
    val recordType: String,
    val metricType: String?,
    val context: String?,
    val segment: String?,
)

private data class Projection(val table: String, val startColumn: String, val endColumn: String? = null)

private fun projectionFor(recordType: String): Projection = when (recordType) {
    RecordTypes.STEP_INTERVAL -> Projection("step_samples", "start_at", "end_at")
    RecordTypes.SLEEP_SESSION -> Projection("sleep_sessions", "start_at", "end_at")
    RecordTypes.SLEEP_SUMMARY -> Projection("sleep_summaries", "start_at", "end_at")
    RecordTypes.ACTIVITY_SUMMARY -> Projection("activity_summaries", "date")
    RecordTypes.BLOOD_PRESSURE -> Projection("blood_pressure_measurements", "measured_at")
    RecordTypes.SCALAR -> Projection("scalar_samples", "measured_at")
    else -> error("Unsupported correction record type: $recordType")
}

private const val CHUNK_SIZE = 1_000
