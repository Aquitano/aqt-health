package me.aquitano.health.application.metric.steps.derived

import me.aquitano.health.application.DerivedRebuildExecutor
import me.aquitano.health.application.DerivedRebuildRequest
import me.aquitano.health.application.MetricCatalogBootstrap
import me.aquitano.health.application.metric.common.CanonicalIntervalCandidate
import me.aquitano.health.application.metric.common.canonicalIntervalRows
import me.aquitano.health.application.metric.steps.repository.CanonicalStepBucketContributionOutput
import me.aquitano.health.application.metric.steps.repository.CanonicalStepDerivationRepository
import me.aquitano.health.application.metric.steps.repository.CanonicalStepOutput
import me.aquitano.health.application.metric.steps.repository.CanonicalStepSampleOutput
import me.aquitano.health.application.metric.steps.repository.StepSampleRow
import me.aquitano.health.domain.MetricFamilies
import me.aquitano.health.infrastructure.database.suspendDbTransaction
import me.aquitano.health.shared.normalizeProviderCode
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

const val CANONICAL_STEP_ALGORITHM_VERSION = 1

private const val UNKNOWN_PROVIDER_RANK = 10_000
private const val MAX_SNAPSHOT_ATTEMPTS = 3

class CanonicalStepDerivationService(
    private val database: Database,
    private val repository: CanonicalStepDerivationRepository,
) : DerivedRebuildExecutor {
    override suspend fun rebuild(
        requests: List<DerivedRebuildRequest>,
        computedAt: Instant,
    ) = recompute(requests.flatMapTo(sortedSetOf()) { it.affectedStepDates }, computedAt)

    suspend fun recompute(
        dates: Set<LocalDate>,
        computedAt: Instant,
    ) {
        dates.forEach { date ->
            val persisted = (1..MAX_SNAPSHOT_ATTEMPTS).any { recomputeFromCurrentSamples(date, computedAt) }
            check(persisted) { "Step samples kept changing while deriving $date; retry required" }
        }
    }

    /** Returns false when raw samples changed between the read and the locked persist. */
    private suspend fun recomputeFromCurrentSamples(
        date: LocalDate,
        computedAt: Instant,
    ): Boolean {
        val dayStart = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        val dayEnd = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val (rawSamples, metadata) =
            suspendDbTransaction(db = database) {
                val rows = repository.listRawSamplesForDay(dayStart, dayEnd)
                rows to repository.sourceMetadataFor(rows.map { it.sourceInstanceId }.toSet())
            }
        val sampleIds = rawSamples.mapTo(hashSetOf()) { it.id }
        val providerRanks = metadata.mapValues { stepProviderRank(it.value.provider) }
        val preparedSamples =
            rawSamples
                .map { preparedCanonicalSample(it, providerRanks) }
                .sortedWith(
                    compareBy<PreparedCanonicalStepSample> { it.row.startAt }
                        .thenBy { it.row.endAt }
                        .thenBy { it.row.id },
                )

        val canonicalSamples =
            canonicalIntervalRows(
                rows = preparedSamples.map { it.asIntervalCandidate() },
                choosePreferred = { left, right ->
                    if (stepPreference.compare(left.row, right.row) <= 0) left else right
                },
            )
        val googleSourceIds = metadata.filterValues { normalizeProviderCode(it.provider) == "google_health" }.keys
        val spans =
            resolveGoogleStepSpans(canonicalSamples.map { it.row }, googleSourceIds)
                .filter { it.startAt.isBefore(dayEnd) && dayStart.isBefore(it.endAt) }
        val durations = canonicalSamples.associate { it.row.id to it.durationSeconds }
        val contributions = linkedMapOf<Pair<Int, Instant>, CanonicalStepBucketContributionOutput>()
        spans.forEach { span ->
            bucketContributions(date, dayStart, dayEnd, span, durations.getValue(span.sample.id), computedAt)
                .forEach { contribution ->
                    val key = contribution.sampleId to contribution.bucketStartAt
                    val previous = contributions[key]
                    contributions[key] =
                        if (previous == null) {
                            contribution
                        } else {
                            previous.copy(value = previous.value + contribution.value)
                        }
                }
        }
        val output =
            CanonicalStepOutput(
                date = date,
                algorithmVersion = CANONICAL_STEP_ALGORITHM_VERSION,
                computedAt = computedAt,
                samples =
                    spans.map { it.sample }.distinctBy { it.id }.map {
                        CanonicalStepSampleOutput(
                            sampleId = it.id,
                            sourceInstanceId = it.sourceInstanceId,
                            startAt = it.startAt,
                            endAt = it.endAt,
                            steps = it.steps,
                        )
                    },
                bucketContributions = contributions.values.toList(),
            )
        return suspendDbTransaction(db = database) {
            // Serialize persistence per date, then reject computations made from stale raw rows.
            exec("SELECT pg_advisory_xact_lock(384729, ${date.toEpochDay().toInt()})")
            if (repository.rawSampleIdsForDay(dayStart, dayEnd) != sampleIds) return@suspendDbTransaction false
            repository.persistCanonicalOutput(output)
            true
        }
    }

    private fun bucketContributions(
        date: LocalDate,
        dayStart: Instant,
        dayEnd: Instant,
        span: StepAllocationSpan,
        durationSeconds: Double,
        computedAt: Instant,
    ): List<CanonicalStepBucketContributionOutput> {
        if (durationSeconds <= 0) return emptyList()
        val sample = span.sample
        val contributions = mutableListOf<CanonicalStepBucketContributionOutput>()
        val firstBucket = Duration.between(dayStart, maxOf(dayStart, span.startAt)).seconds / 900
        var bucketStart = dayStart.plusSeconds(firstBucket * 900)
        val lastEnd = minOf(dayEnd, span.endAt)
        while (bucketStart.isBefore(lastEnd)) {
            val bucketEnd = minOf(bucketStart.plus(Duration.ofMinutes(15)), dayEnd)
            if (span.startAt.isBefore(bucketEnd) && bucketStart.isBefore(span.endAt)) {
                contributions +=
                    CanonicalStepBucketContributionOutput(
                        date = date,
                        sourceInstanceId = sample.sourceInstanceId,
                        sampleId = sample.id,
                        bucketStartAt = bucketStart,
                        bucketEndAt = bucketEnd,
                        value =
                            allocatedSteps(
                                sample.startAt,
                                sample.endAt,
                                sample.steps,
                                maxOf(bucketStart, span.startAt),
                                minOf(bucketEnd, span.endAt),
                                durationSeconds,
                            ).toDouble(),
                        computedAt = computedAt,
                    )
            }
            bucketStart = bucketEnd
        }
        return contributions
    }

    // Ranks providers for canonical step selection from the same list MetricCatalogBootstrap
    // seeds into provider_ranks, so the in-memory and database rankings cannot drift.
    private fun stepProviderRank(provider: String?): Int {
        val normalized =
            provider
                ?.let(::normalizeProviderCode)
                ?.takeIf { it.isNotBlank() }
                ?: return UNKNOWN_PROVIDER_RANK
        val index =
            MetricCatalogBootstrap.providerRanks
                .getValue(MetricFamilies.STEPS)
                .indexOf(normalized)
        return if (index >= 0) index else UNKNOWN_PROVIDER_RANK
    }

    private fun preparedCanonicalSample(
        row: StepSampleRow,
        providerRanks: Map<Int, Int>,
    ): PreparedCanonicalStepSample {
        val duration = secondsBetween(row.startAt, row.endAt)
        return PreparedCanonicalStepSample(
            row = row,
            durationSeconds = duration,
            providerRank = providerRanks[row.sourceInstanceId] ?: UNKNOWN_PROVIDER_RANK,
            stepsPerSecond = row.steps.toDouble() / duration,
        )
    }
}

private data class PreparedCanonicalStepSample(
    val row: StepSampleRow,
    val durationSeconds: Double,
    val providerRank: Int,
    val stepsPerSecond: Double,
) {
    fun asIntervalCandidate(): CanonicalIntervalCandidate<PreparedCanonicalStepSample> =
        CanonicalIntervalCandidate(
            row = this,
            sourceInstanceId = row.sourceInstanceId,
            startAt = row.startAt,
            endAt = row.endAt,
        )
}

private val stepPreference =
    compareBy<PreparedCanonicalStepSample> { it.providerRank }
        .thenBy { it.durationSeconds }
        .thenByDescending { it.stepsPerSecond }
        .thenBy { it.row.id }
