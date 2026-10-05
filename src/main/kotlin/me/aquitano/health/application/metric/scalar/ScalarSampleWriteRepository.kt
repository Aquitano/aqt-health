package me.aquitano.health.application.metric.scalar

import me.aquitano.health.domain.ScalarSampleRecord
import me.aquitano.health.infrastructure.database.tables.ScalarSamplesTable
import me.aquitano.health.infrastructure.database.toDbTimestamp
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import java.time.Instant

data class ScalarSampleWrite(
    val ingestionRecordId: Int,
    val record: ScalarSampleRecord,
)

class ScalarSampleWriteRepository {
    /**
     * Returns one metric type per value actually inserted (duplicates are skipped).
     * Duplicates are detected before inserting (conflict-ignored rows can't be told apart from
     * inserted ones in a multi-row statement), against two keys that mirror the DB unique indexes:
     *  - rows that carry a provider record id -> scalar_samples_provider_record_uq
     *  - rows without one -> scalar_samples_natural_key_uq (source, metric type, measured at, context, segment)
     * so re-syncs of id-less feeds (derived metrics, some Google data) stop accumulating duplicates.
     * `ignore = true` stays on the insert as a guard against concurrent writers.
     */
    fun insertScalarSamples(
        sourceInstanceId: Int,
        writes: List<ScalarSampleWrite>,
        now: Instant,
    ): List<String> {
        val seenKeys = existingKeys(sourceInstanceId, writes)
        val toInsert = writes.filter { write -> seenKeys.add(write.uniqueKey()) }
        toInsert.chunked(INSERT_CHUNK_SIZE).forEach { chunk ->
            ScalarSamplesTable.batchInsert(
                chunk,
                useMultiRowValues = true,
                ignore = true,
                shouldReturnGeneratedValues = false,
            ) { (ingestionRecordId, record) ->
                this[ScalarSamplesTable.sourceInstanceId] = sourceInstanceId
                this[ScalarSamplesTable.ingestionRecordId] = ingestionRecordId
                this[ScalarSamplesTable.providerRecordId] = record.providerRecordId
                this[ScalarSamplesTable.measuredAt] = record.measuredAt.toDbTimestamp()
                this[ScalarSamplesTable.metricType] = record.value.metricType
                this[ScalarSamplesTable.value] = record.value.value
                this[ScalarSamplesTable.context] = record.value.context
                this[ScalarSamplesTable.segment] = record.value.segment
                this[ScalarSamplesTable.createdAt] = now.toDbTimestamp()
            }
        }
        return toInsert.map { it.record.value.metricType }
    }

    private fun existingKeys(
        sourceInstanceId: Int,
        writes: List<ScalarSampleWrite>,
    ): MutableSet<SampleKey> {
        val keys = hashSetOf<SampleKey>()

        val providerRecordIds = writes.mapNotNullTo(linkedSetOf()) { it.record.providerRecordId }
        providerRecordIds.toList().chunked(INSERT_CHUNK_SIZE).forEach { chunk ->
            ScalarSamplesTable
                .select(
                    ScalarSamplesTable.providerRecordId,
                    ScalarSamplesTable.metricType,
                    ScalarSamplesTable.context,
                    ScalarSamplesTable.segment,
                ).where {
                    (ScalarSamplesTable.sourceInstanceId eq sourceInstanceId) and
                        (ScalarSamplesTable.providerRecordId inList chunk)
                }.forEach { row ->
                    row[ScalarSamplesTable.providerRecordId]?.let { providerRecordId ->
                        keys +=
                            SampleKey.ByRecord(
                                providerRecordId = providerRecordId,
                                metricType = row[ScalarSamplesTable.metricType],
                                context = row[ScalarSamplesTable.context] ?: "",
                                segment = row[ScalarSamplesTable.segment] ?: "",
                            )
                    }
                }
        }

        // Id-less rows can't use the provider-record key, so dedupe them on the natural key. The
        // DB query keys back to existing NULL-id rows (matching scalar_samples_natural_key_uq) so a
        // re-sync of a feed without stable ids doesn't pile up duplicate samples.
        val measuredAtsWithoutId =
            writes
                .filter { it.record.providerRecordId == null }
                .mapTo(linkedSetOf()) { it.record.measuredAt }
        measuredAtsWithoutId.toList().chunked(INSERT_CHUNK_SIZE).forEach { chunk ->
            ScalarSamplesTable
                .select(
                    ScalarSamplesTable.measuredAt,
                    ScalarSamplesTable.metricType,
                    ScalarSamplesTable.context,
                    ScalarSamplesTable.segment,
                ).where {
                    (ScalarSamplesTable.sourceInstanceId eq sourceInstanceId) and
                        ScalarSamplesTable.providerRecordId.isNull() and
                        (ScalarSamplesTable.measuredAt inList chunk.map { it.toDbTimestamp() })
                }.forEach { row ->
                    keys +=
                        SampleKey.ByNatural(
                            measuredAt = row[ScalarSamplesTable.measuredAt].toInstant(),
                            metricType = row[ScalarSamplesTable.metricType],
                            context = row[ScalarSamplesTable.context] ?: "",
                            segment = row[ScalarSamplesTable.segment] ?: "",
                        )
                }
        }
        return keys
    }
}

/**
 * Dedup key. Rows carrying a provider record id mirror scalar_samples_provider_record_uq; id-less
 * rows fall back to the natural key (scalar_samples_natural_key_uq). Both include context, so a
 * provider emitting one record id under two contexts keeps both rows; NULL context and segment
 * coalesce to '' exactly as the indexes do.
 */
private sealed interface SampleKey {
    data class ByRecord(
        val providerRecordId: String,
        val metricType: String,
        val context: String,
        val segment: String,
    ) : SampleKey

    data class ByNatural(
        val measuredAt: Instant,
        val metricType: String,
        val context: String,
        val segment: String,
    ) : SampleKey
}

private fun ScalarSampleWrite.uniqueKey(): SampleKey {
    val value = record.value
    return record.providerRecordId?.let { providerRecordId ->
        SampleKey.ByRecord(
            providerRecordId = providerRecordId,
            metricType = value.metricType,
            context = value.context ?: "",
            segment = value.segment ?: "",
        )
    } ?: SampleKey.ByNatural(
        measuredAt = record.measuredAt,
        metricType = value.metricType,
        context = value.context ?: "",
        segment = value.segment ?: "",
    )
}

private const val INSERT_CHUNK_SIZE = 1_000
