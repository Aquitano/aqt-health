package me.aquitano.health.application.metric.common

import me.aquitano.health.application.metric.activity.repository.ActivitySummaryWriteRepository
import me.aquitano.health.application.metric.cardiovascular.repository.CardiovascularWriteRepository
import me.aquitano.health.application.metric.common.repository.ProviderRecordCorrections
import me.aquitano.health.application.metric.scalar.ScalarSampleWrite
import me.aquitano.health.application.metric.scalar.ScalarSampleWriteRepository
import me.aquitano.health.application.metric.sleep.repository.SleepWriteRepository
import me.aquitano.health.application.metric.steps.repository.StepWriteRepository
import me.aquitano.health.application.stepRebuildDates
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
        val affectedStepDates =
            prepared.replacedSpans.flatMapTo(linkedSetOf()) { stepRebuildDates(it.recordType, it.startAt, it.endAt) }
        val scalarWrites = mutableListOf<ScalarSampleWrite>()
        val googleStepDecisions = prepared.googleStepDecisions.toMutableMap()

        prepared.writes.forEach { (ingestionRecordId, record) ->
            val inserted =
                when (record) {
                    is ScalarSampleRecord -> {
                        scalarWrites += ScalarSampleWrite(ingestionRecordId, record)
                        return@forEach
                    }

                    is StepIntervalRecord -> {
                        MetricCreatedCounts.of(StructuralMetricKinds.STEP_SAMPLES to 1).takeIf {
                            stepWriteRepository.insertStepSample(
                                provider,
                                sourceInstanceId,
                                ingestionRecordId,
                                record,
                                now,
                                preserveAcceptance = ingestionRecordId in prepared.acceptedGoogleStepIds,
                            )
                        }
                    }

                    is SleepSessionRecord -> {
                        sleepWriteRepository.insertSleepSession(sourceInstanceId, ingestionRecordId, record, now)?.let {
                            MetricCreatedCounts.of(
                                StructuralMetricKinds.SLEEP_SESSIONS to 1,
                                StructuralMetricKinds.SLEEP_STAGES to record.stages.size,
                            )
                        }
                    }

                    is ActivitySummaryRecord -> {
                        MetricCreatedCounts.of(StructuralMetricKinds.ACTIVITY_SUMMARIES to 1).takeIf {
                            activitySummaryWriteRepository.insertActivitySummary(sourceInstanceId, ingestionRecordId, record, now)
                        }
                    }

                    is SleepSummaryRecord -> {
                        MetricCreatedCounts.of(StructuralMetricKinds.SLEEP_SUMMARIES to 1).takeIf {
                            sleepWriteRepository.insertSleepSummary(sourceInstanceId, ingestionRecordId, record, now)
                        }
                    }

                    is BloodPressureRecord -> {
                        MetricCreatedCounts.of(StructuralMetricKinds.BLOOD_PRESSURE_MEASUREMENTS to 1).takeIf {
                            cardiovascularWriteRepository.insertBloodPressure(sourceInstanceId, ingestionRecordId, record, now)
                        }
                    }
                }
            if (ingestionRecordId in prepared.googleStepRecordIds) {
                googleStepDecisions[ingestionRecordId] =
                    ingestionRecordId in prepared.acceptedGoogleStepIds || inserted != null
            }
            if (inserted == null) {
                duplicateSkipped++
                return@forEach
            }
            created += inserted
            record.recordStartAt?.let { affectedStepDates += stepRebuildDates(record.recordType, it, record.recordEndAt) }
        }

        if (scalarWrites.isNotEmpty()) {
            val insertedTypes = scalarSampleWriteRepository.insertScalarSamples(sourceInstanceId, scalarWrites, now)
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
}

data class MetricWriteResult(
    val created: MetricCreatedCounts = MetricCreatedCounts(),
    val duplicateSkipped: Int = 0,
    val affectedStepDates: Set<LocalDate> = emptySet(),
)
