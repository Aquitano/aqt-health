package me.aquitano.health.application.metric.steps.repository

import me.aquitano.health.domain.StepIntervalRecord
import me.aquitano.health.infrastructure.database.tables.StepSamplesTable
import me.aquitano.health.infrastructure.database.toDbTimestamp
import me.aquitano.health.shared.normalizeProviderCode
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnoreAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import java.time.Instant
import java.time.OffsetDateTime

private const val GOOGLE_HEALTH_PROVIDER_CODE = "google_health"

class StepWriteRepository {
    fun insertStepSample(
        provider: String,
        sourceInstanceId: Int,
        ingestionRecordId: Int,
        record: StepIntervalRecord,
        now: Instant,
    ): Boolean {
        if (normalizeProviderCode(provider) == GOOGLE_HEALTH_PROVIDER_CODE && stepSampleOverlaps(
                sourceInstanceId,
                record,
            )
        ) return false

        return StepSamplesTable.insertIgnoreAndGetId {
            it[this.sourceInstanceId] = sourceInstanceId
            it[this.ingestionRecordId] = ingestionRecordId
            it[providerRecordId] = record.providerRecordId
            it[startAt] = record.startAt.toDbTimestamp()
            it[endAt] = record.endAt.toDbTimestamp()
            it[steps] = record.steps
            it[createdAt] = now.toDbTimestamp()
        } != null
    }

    /** True when the instance already holds a step sample whose `[start, end)` meets the record's. */
    private fun stepSampleOverlaps(
        sourceInstanceId: Int,
        record: StepIntervalRecord,
    ): Boolean =
        StepSamplesTable.select(StepSamplesTable.id)
            .where {
                (StepSamplesTable.sourceInstanceId eq sourceInstanceId) and
                    StepTimeRangeOverlaps(
                        record.startAt.toDbTimestamp(),
                        record.endAt.toDbTimestamp(),
                    )
            }
            .limit(1)
            .any()
}

/**
 * `step_samples.time_range && tstzrange(start, end, '[)')`. `time_range` is a stored generated
 * column that Exposed does not model, so the predicate renders by hand; using the range operator is
 * what lets the query take `step_samples_source_instance_time_range_gist_idx` instead of scanning
 * every sample of the instance.
 */
private class StepTimeRangeOverlaps(
    private val startAt: OffsetDateTime,
    private val endAt: OffsetDateTime,
) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append("${StepSamplesTable.tableName}.time_range && tstzrange(")
        registerArgument(StepSamplesTable.startAt, startAt)
        append(", ")
        registerArgument(StepSamplesTable.endAt, endAt)
        append(", '[)')")
    }
}
