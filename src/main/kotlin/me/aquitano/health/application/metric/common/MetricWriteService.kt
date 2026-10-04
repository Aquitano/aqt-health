package me.aquitano.health.application.metric.common

import me.aquitano.health.application.DerivedRebuildModuleRegistry
import me.aquitano.health.application.metric.activity.repository.ActivitySummaryWriteRepository
import me.aquitano.health.application.metric.cardiovascular.repository.CardiovascularWriteRepository
import me.aquitano.health.application.metric.common.repository.ProviderRecordCorrections
import me.aquitano.health.application.metric.scalar.ScalarSampleWrite
import me.aquitano.health.application.metric.scalar.ScalarSampleWriteRepository
import me.aquitano.health.application.metric.sleep.repository.SleepWriteRepository
import me.aquitano.health.application.metric.steps.repository.StepWriteRepository
import me.aquitano.health.domain.ActivitySummaryRecord
import me.aquitano.health.domain.BloodPressureRecord
import me.aquitano.health.domain.HealthRecord
import me.aquitano.health.domain.MetricCreatedCounts
import me.aquitano.health.domain.ScalarSampleRecord
import me.aquitano.health.domain.SleepSessionRecord
import me.aquitano.health.domain.SleepSummaryRecord
import me.aquitano.health.domain.StepIntervalRecord
import me.aquitano.health.domain.StructuralMetricKinds
import java.time.Instant
import java.time.LocalDate

data class MetricWrite(
    val ingestionRecordId: Int,
    val record: HealthRecord,
)

class MetricWriteService(
    private val stepWriteRepository: StepWriteRepository,
    private val sleepWriteRepository: SleepWriteRepository,
    private val activitySummaryWriteRepository: ActivitySummaryWriteRepository,
    private val cardiovascularWriteRepository: CardiovascularWriteRepository,
    private val scalarSampleWriteRepository: ScalarSampleWriteRepository,
    private val derivedRebuildRegistry: DerivedRebuildModuleRegistry,
) {
    private val corrections = ProviderRecordCorrections()

    /**
     * Writes a whole batch, bulk-inserting scalar samples in chunked multi-row statements
     * instead of one round trip per sample. Structural records (sleep, steps, ...) keep the
     * per-record path because they are low-volume and need per-record skip semantics.
     */
    fun writeAll(
        provider: String,
        sourceInstanceId: Int,
        writes: List<MetricWrite>,
        now: Instant,
    ): MetricWriteResult {
        val prepared = corrections.prepare(provider, sourceInstanceId, writes)
        var created = MetricCreatedCounts()
        var duplicateSkipped = writes.size - prepared.writes.size
        val affectedStepDates = linkedSetOf<LocalDate>()
        prepared.replacedSpans.forEach { previous ->
            affectedStepDates +=
                derivedRebuildRegistry.affectedDatesFor(previous.recordType, previous.startAt, previous.endAt)
        }
        val scalarWrites = mutableListOf<ScalarSampleWrite>()
        val googleStepDecisions = prepared.googleStepDecisions.toMutableMap()

        prepared.writes.forEach { entry ->
            if (entry.record is ScalarSampleRecord) {
                scalarWrites += ScalarSampleWrite(entry.ingestionRecordId, entry.record)
                return@forEach
            }
            val result =
                writePrepared(
                    provider,
                    sourceInstanceId,
                    entry.ingestionRecordId,
                    entry.record,
                    now,
                    preserveAcceptance = entry.ingestionRecordId in prepared.acceptedGoogleStepIds,
                )
            if (entry.ingestionRecordId in prepared.googleStepRecordIds) {
                googleStepDecisions[entry.ingestionRecordId] =
                    entry.ingestionRecordId in prepared.acceptedGoogleStepIds ||
                    result.created.counts[StructuralMetricKinds.STEP_SAMPLES] == 1
            }
            created += result.created
            duplicateSkipped += result.duplicateSkipped
            affectedStepDates += result.affectedStepDates
        }

        if (scalarWrites.isNotEmpty()) {
            val insertedTypes =
                scalarSampleWriteRepository.insertScalarSamples(
                    sourceInstanceId,
                    scalarWrites,
                    now,
                )
            // insertedTypes is the repository's in-memory dedup decision and is authoritative for
            // these counts: created = rows that passed dedup, duplicateSkipped = the rest. The
            // insert's ignore=true can additionally drop a concurrent writer's row after the
            // pre-check; that rare case is counted as created here because a multi-row statement
            // can't distinguish inserted from conflict-ignored rows without an extra round trip.
            created += MetricCreatedCounts(insertedTypes.groupingBy { it }.eachCount())
            duplicateSkipped += scalarWrites.size - insertedTypes.size
        }

        corrections.recordGoogleStepDecisions(googleStepDecisions, prepared.googleStepPriorities)
        return MetricWriteResult(
            created = created,
            duplicateSkipped = duplicateSkipped,
            affectedStepDates = affectedStepDates,
        )
    }

    private fun writePrepared(
        provider: String,
        sourceInstanceId: Int,
        ingestionRecordId: Int,
        record: HealthRecord,
        now: Instant,
        preserveAcceptance: Boolean,
    ): MetricWriteResult =
        when (record) {
            is StepIntervalRecord -> {
                writeStepInterval(
                    provider,
                    sourceInstanceId,
                    ingestionRecordId,
                    record,
                    now,
                    preserveAcceptance,
                )
            }

            is SleepSessionRecord -> {
                writeSleepSession(
                    sourceInstanceId,
                    ingestionRecordId,
                    record,
                    now,
                )
            }

            is ActivitySummaryRecord -> {
                writeActivitySummary(
                    sourceInstanceId,
                    ingestionRecordId,
                    record,
                    now,
                )
            }

            is SleepSummaryRecord -> {
                writeSleepSummary(
                    sourceInstanceId,
                    ingestionRecordId,
                    record,
                    now,
                )
            }

            is BloodPressureRecord -> {
                writeBloodPressure(
                    sourceInstanceId,
                    ingestionRecordId,
                    record,
                    now,
                )
            }

            is ScalarSampleRecord -> {
                writeScalarSamples(
                    sourceInstanceId,
                    ingestionRecordId,
                    record,
                    now,
                )
            }
        }

    private fun writeStepInterval(
        provider: String,
        sourceInstanceId: Int,
        ingestionRecordId: Int,
        record: StepIntervalRecord,
        now: Instant,
        preserveAcceptance: Boolean,
    ): MetricWriteResult {
        val inserted =
            stepWriteRepository.insertStepSample(
                provider,
                sourceInstanceId,
                ingestionRecordId,
                record,
                now,
                preserveAcceptance,
            )
        return if (inserted) {
            MetricWriteResult(
                created = MetricCreatedCounts.of(StructuralMetricKinds.STEP_SAMPLES to 1),
                affectedStepDates =
                    derivedRebuildRegistry.affectedDatesFor(
                        record.recordType,
                        record.startAt,
                        record.endAt,
                    ),
            )
        } else {
            MetricWriteResult(duplicateSkipped = 1)
        }
    }

    private fun writeSleepSession(
        sourceInstanceId: Int,
        ingestionRecordId: Int,
        record: SleepSessionRecord,
        now: Instant,
    ): MetricWriteResult {
        val sessionId =
            sleepWriteRepository.insertSleepSession(
                sourceInstanceId,
                ingestionRecordId,
                record,
                now,
            )
        return if (sessionId != null) {
            MetricWriteResult(
                created =
                    MetricCreatedCounts.of(
                        StructuralMetricKinds.SLEEP_SESSIONS to 1,
                        StructuralMetricKinds.SLEEP_STAGES to record.stages.size,
                    ),
                affectedStepDates =
                    derivedRebuildRegistry.affectedDatesFor(
                        record.recordType,
                        record.startAt,
                        record.endAt,
                    ),
            )
        } else {
            MetricWriteResult(duplicateSkipped = 1)
        }
    }

    private fun writeActivitySummary(
        sourceInstanceId: Int,
        ingestionRecordId: Int,
        record: ActivitySummaryRecord,
        now: Instant,
    ): MetricWriteResult {
        val inserted =
            activitySummaryWriteRepository.insertActivitySummary(
                sourceInstanceId,
                ingestionRecordId,
                record,
                now,
            )
        return if (inserted) {
            MetricWriteResult(created = MetricCreatedCounts.of(StructuralMetricKinds.ACTIVITY_SUMMARIES to 1))
        } else {
            MetricWriteResult(duplicateSkipped = 1)
        }
    }

    private fun writeSleepSummary(
        sourceInstanceId: Int,
        ingestionRecordId: Int,
        record: SleepSummaryRecord,
        now: Instant,
    ): MetricWriteResult {
        val inserted =
            sleepWriteRepository.insertSleepSummary(
                sourceInstanceId,
                ingestionRecordId,
                record,
                now,
            )
        return if (inserted) {
            MetricWriteResult(created = MetricCreatedCounts.of(StructuralMetricKinds.SLEEP_SUMMARIES to 1))
        } else {
            MetricWriteResult(duplicateSkipped = 1)
        }
    }

    private fun writeBloodPressure(
        sourceInstanceId: Int,
        ingestionRecordId: Int,
        record: BloodPressureRecord,
        now: Instant,
    ): MetricWriteResult {
        val inserted =
            cardiovascularWriteRepository.insertBloodPressure(
                sourceInstanceId,
                ingestionRecordId,
                record,
                now,
            )
        return if (inserted) {
            MetricWriteResult(created = MetricCreatedCounts.of(StructuralMetricKinds.BLOOD_PRESSURE_MEASUREMENTS to 1))
        } else {
            MetricWriteResult(duplicateSkipped = 1)
        }
    }

    private fun writeScalarSamples(
        sourceInstanceId: Int,
        ingestionRecordId: Int,
        record: ScalarSampleRecord,
        now: Instant,
    ): MetricWriteResult {
        val insertedTypes =
            scalarSampleWriteRepository.insertScalarSamples(
                sourceInstanceId,
                ingestionRecordId,
                record,
                now,
            )
        val counts =
            insertedTypes
                .groupingBy { it }
                .eachCount()
        return MetricWriteResult(
            created = MetricCreatedCounts(counts),
            duplicateSkipped = if (insertedTypes.isEmpty()) 1 else 0,
        )
    }
}

data class MetricWriteResult(
    val created: MetricCreatedCounts = MetricCreatedCounts(),
    val duplicateSkipped: Int = 0,
    val affectedStepDates: Set<LocalDate> = emptySet(),
)
