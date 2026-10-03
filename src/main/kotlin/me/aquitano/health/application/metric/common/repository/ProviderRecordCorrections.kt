package me.aquitano.health.application.metric.common.repository

import me.aquitano.health.application.metric.common.MetricWrite
import me.aquitano.health.domain.RecordTypes
import me.aquitano.health.infrastructure.repositories.sameScalarIdentity
import me.aquitano.health.infrastructure.repositories.scalarContext
import me.aquitano.health.shared.normalizeProviderCode
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.Instant

/** A previous projection's dates must be invalidated when a correction moves the record. */
data class ReplacedRecordSpan(val recordType: String, val startAt: Instant, val endAt: Instant?)

data class PreparedMetricWrites(
    val writes: List<MetricWrite>,
    val replacedSpans: List<ReplacedRecordSpan>,
    val acceptedGoogleStepIds: Set<Int> = emptySet(),
    val googleStepRecordIds: Set<Int> = emptySet(),
    val googleStepDecisions: Map<Int, Boolean> = emptyMap(),
    val googleStepPriorities: Map<Int, Int> = emptyMap(),
)

/**
 * Coordinates corrections for all projection writers inside their transaction. The append-only
 * log determines the newest version even when replay has deleted the projection being rebuilt.
 */
class ProviderRecordCorrections {
    fun prepare(provider: String, sourceInstanceId: Int, writes: List<MetricWrite>): PreparedMetricWrites {
        if (writes.isEmpty()) return PreparedMetricWrites(emptyList(), emptyList())
        val transaction = TransactionManager.current()
        // Acceptance metadata may be written even in a replay without a wipe. Acquire the raw-log
        // write lock before the source lock, matching ingestion and wipe-replay lock order.
        transaction.exec("LOCK TABLE ingestion_records IN ROW EXCLUSIVE MODE")
        transaction.exec("SELECT pg_advisory_xact_lock(384730, $sourceInstanceId)")
        val identified = writes.filter { it.record.providerRecordId != null }
        if (identified.isEmpty()) return PreparedMetricWrites(writes, emptyList())

        val googleStepIds = if (normalizeProviderCode(provider) == "google_health") {
            identified.filter { it.record.recordType == RecordTypes.STEP_INTERVAL }
                .mapTo(hashSetOf()) { it.ingestionRecordId }
        } else emptySet()
        val eligibleIds = hashSetOf<Int>()
        val acceptedGoogleStepIds = hashSetOf<Int>()
        val googleStepDecisions = mutableMapOf<Int, Boolean>()
        val googleStepPriorities = mutableMapOf<Int, Int>()
        identified.chunked(CHUNK_SIZE).forEach { chunk ->
            val ids = chunk.joinToString(",") { it.ingestionRecordId.toString() }
            val priorStep = if (googleStepIds.isNotEmpty()) {
                """LEFT JOIN LATERAL (
                    SELECT older.google_step_projection_accepted AS accepted,
                           older.google_step_allocation_priority_record_id AS priority,
                           older.normalized_record_json = incoming.normalized_record_json AS unchanged
                    FROM ingestion_records older
                    JOIN ingestion_batches batch ON batch.id = older.batch_id
                    WHERE older.provider_record_id = incoming.provider_record_id
                      AND older.record_type = 'step_interval'
                      AND older.id < incoming.id
                      AND batch.source_instance_id = $sourceInstanceId
                      AND batch.status = 'processed'
                    ORDER BY older.id DESC LIMIT 1
                ) previous_step ON TRUE"""
            } else "LEFT JOIN (SELECT NULL::boolean AS accepted, NULL::integer AS priority, FALSE AS unchanged) previous_step ON TRUE"
            transaction.exec(
                """
                SELECT incoming.id, incoming.google_step_projection_accepted AS step_accepted,
                       previous_step.accepted AS previous_step_accepted,
                       COALESCE(incoming.google_step_allocation_priority_record_id,
                           CASE WHEN previous_step.accepted AND previous_step.unchanged THEN previous_step.priority END,
                           incoming.id) AS step_priority
                FROM ingestion_records incoming
                $priorStep
                WHERE incoming.id IN ($ids)
                  AND NOT EXISTS (
                    SELECT 1 FROM ingestion_records newer
                    JOIN ingestion_batches batch ON batch.id = newer.batch_id
                    WHERE newer.provider_record_id = incoming.provider_record_id
                      AND newer.record_type = incoming.record_type
                      AND newer.id > incoming.id
                      AND batch.source_instance_id = $sourceInstanceId
                      AND batch.status = 'processed'
                      AND ${sameScalarIdentity("newer", "incoming")}
                  )
                """.trimIndent()
            ) { rows ->
                while (rows.next()) {
                    val id = rows.getInt("id")
                    if (id in googleStepIds) {
                        val decision = rows.getObject("step_accepted") as Boolean?
                        if (decision == false) continue
                        googleStepPriorities[id] = rows.getInt("step_priority")
                        if (decision == true || rows.getBoolean("previous_step_accepted")) {
                            acceptedGoogleStepIds += id
                        }
                    }
                    eligibleIds += id
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
                            if (incomingId in googleStepIds) {
                                acceptedGoogleStepIds += incomingId
                                googleStepDecisions[incomingId] = true
                            }
                            if (rows.getInt("ingestion_record_id") >= incomingId || rows.getBoolean("unchanged")) {
                                eligibleIds -= incomingId
                            } else {
                                deleteIds += rows.getLong("id")
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
            writes.filter { it.record.providerRecordId == null || it.ingestionRecordId in eligibleIds },
            replacedSpans,
            acceptedGoogleStepIds,
            googleStepIds,
            googleStepDecisions,
            googleStepPriorities,
        )
    }

    fun recordGoogleStepDecisions(decisions: Map<Int, Boolean>, priorities: Map<Int, Int>) {
        decisions.entries.chunked(CHUNK_SIZE).forEach { chunk ->
            val values = chunk.joinToString(",") { (id, accepted) -> "($id, $accepted, ${priorities.getValue(id)})" }
            TransactionManager.current().exec(
                """UPDATE ingestion_records record
                   SET google_step_projection_accepted = decision.accepted,
                       google_step_allocation_priority_record_id = decision.priority
                   FROM (VALUES $values) AS decision(id, accepted, priority)
                   WHERE record.id = decision.id AND record.google_step_projection_accepted IS NULL"""
            )
        }
    }
}

private data class Projection(val table: String, val startColumn: String, val endColumn: String? = null)

private fun projectionFor(recordType: String): Projection = when (recordType) {
    RecordTypes.STEP_INTERVAL -> Projection("step_samples", "start_at", "end_at")
    RecordTypes.SLEEP_SESSION -> Projection("sleep_sessions", "start_at", "end_at")
    RecordTypes.SLEEP_SUMMARY -> Projection("sleep_summaries", "start_at", "end_at")
    RecordTypes.ACTIVITY_SUMMARY -> Projection("activity_summaries", "date::timestamp AT TIME ZONE 'UTC'")
    RecordTypes.BLOOD_PRESSURE -> Projection("blood_pressure_measurements", "measured_at")
    RecordTypes.SCALAR -> Projection("scalar_samples", "measured_at")
    else -> error("Unsupported correction record type: $recordType")
}

private const val CHUNK_SIZE = 1_000
