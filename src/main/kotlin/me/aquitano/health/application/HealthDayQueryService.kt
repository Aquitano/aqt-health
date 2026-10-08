package me.aquitano.health.application

import me.aquitano.health.api.dto.*
import me.aquitano.health.application.metric.common.QueryParams
import me.aquitano.health.application.metric.common.repository.ReadFilters
import me.aquitano.health.application.metric.common.singleSource
import me.aquitano.health.application.metric.common.toResponse
import me.aquitano.health.application.metric.scalar.ScalarSampleReadRepository
import me.aquitano.health.application.metric.scalar.toScalarResponse
import me.aquitano.health.application.metric.sleep.repository.SleepRepository
import me.aquitano.health.application.metric.steps.repository.CanonicalStepDerivationRepository
import me.aquitano.health.domain.BodyMetricTypes
import me.aquitano.health.domain.RequestValidationException
import me.aquitano.health.domain.ScalarMetricTypes
import me.aquitano.health.domain.ValidationIssue
import me.aquitano.health.domain.ValidationIssueCodes
import me.aquitano.health.infrastructure.database.suspendDbTransaction
import me.aquitano.health.shared.SortDirection
import org.jetbrains.exposed.v1.jdbc.Database
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

private data class HealthDayQueryContext(
    val date: LocalDate,
    val timezone: ZoneId,
    val from: Instant,
    val to: Instant,
    val provider: String?,
    val providerInstanceId: String?,
    val includeSource: Boolean,
)

class HealthDayQueryService(
    private val database: Database,
    private val canonicalStepRepository: CanonicalStepDerivationRepository,
    private val scalarRepository: ScalarSampleReadRepository,
    private val sleepRepository: SleepRepository,
) {
    suspend fun getHealthDay(
        params: QueryParams,
        now: Instant,
    ): HealthDayResponse {
        val timezone = params.timezone()
        val date =
            params.dateOrToday("date", now, timezone)
                ?: throw RequestValidationException(field = "date", code = ValidationIssueCodes.Required, message = "is required")
        val modules = parseModules(params.required("modules"))
        val from = date.atStartOfDay(timezone).toInstant()
        val to = date.plusDays(1).atStartOfDay(timezone).toInstant()
        val context =
            HealthDayQueryContext(
                date = date,
                timezone = timezone,
                from = from,
                to = to,
                provider = params.optional("provider"),
                providerInstanceId = params.optional("providerInstanceId"),
                includeSource = params.boolean("includeSource", default = false),
            )

        return suspendDbTransaction(db = database) {
            val empty =
                HealthDayResponse(
                    date = date.toString(),
                    timezone = timezone.id,
                    from = from.toString(),
                    to = to.toString(),
                    modules = modules,
                )
            modules.fold(empty) { response, module ->
                when (module) {
                    HealthDayModuleName.Steps -> response.copy(steps = steps(context))
                    HealthDayModuleName.HeartRate -> response.copy(heartRate = heartRate(context))
                    HealthDayModuleName.Weight -> response.copy(weight = weight(context))
                    HealthDayModuleName.Sleep -> response.copy(sleep = sleep(context))
                }
            }
        }
    }

    private fun parseModules(value: String): List<HealthDayModuleName> {
        val names =
            value
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        if (names.isEmpty()) {
            throw RequestValidationException(field = "modules", code = ValidationIssueCodes.Required, message = "must contain at least one module")
        }
        val modules = names.associateWith(HealthDayModuleName::fromWireName)
        val unsupported = modules.filterValues { it == null }.keys
        if (unsupported.isNotEmpty()) {
            throw RequestValidationException(
                unsupported.map {
                    ValidationIssue(
                        field = "modules",
                        code = ValidationIssueCodes.UnsupportedValue,
                        message = "unsupported module $it",
                    )
                },
            )
        }
        return modules.values.filterNotNull()
    }

    private fun steps(context: HealthDayQueryContext): HealthDayStepsResponse {
        val filters = context.filters()
        val (samplesBySource, sourceMetadata) =
            canonicalStepRepository.countCanonicalStepSamplesBySource(filters)
        val buckets = context.buckets()
        val values = DoubleArray(buckets.size)
        val counts = IntArray(buckets.size)

        val byStart = buckets.withIndex().associate { (index, bucket) -> bucket.first to index }
        canonicalStepRepository
            .listBucketContributions(filters)
            .forEach { contribution ->
                val index = byStart[contribution.bucketStartAt]
                if (index != null) {
                    values[index] += contribution.value
                    counts[index] += 1
                }
            }

        return HealthDayStepsResponse(
            total = values.sum().roundToInt(),
            sampleCount = samplesBySource.values.sum(),
            buckets =
                buckets.mapIndexed { index, (start, end) ->
                    HealthDayBucketResponse(
                        startAt = start.toString(),
                        endAt = end.toString(),
                        value = if (counts[index] == 0) null else values[index],
                        count = counts[index],
                    )
                },
            source = samplesBySource.keys.singleSource(sourceMetadata) { it },
        )
    }

    private fun heartRate(context: HealthDayQueryContext): HealthDayHeartRateResponse {
        val (samples, sourceMetadata) =
            scalarRepository.list(context.filters(), setOf(ScalarMetricTypes.HEART_RATE), canonical = true)
        val values = samples.map { it.value }
        val buckets = context.buckets()
        val totals = DoubleArray(buckets.size)
        val counts = IntArray(buckets.size)

        samples.forEach { sample ->
            val index = Duration.between(context.from, sample.measuredAt).toMinutes().toInt() / 15
            if (index in buckets.indices) {
                totals[index] += sample.value
                counts[index] += 1
            }
        }

        return HealthDayHeartRateResponse(
            count = values.size,
            minBpm = values.minOrNull()?.roundToInt(),
            maxBpm = values.maxOrNull()?.roundToInt(),
            avgBpm = values.takeIf { it.isNotEmpty() }?.average()?.toTwoDecimals(),
            latest = samples.lastOrNull()?.toScalarResponse(sourceMetadata),
            buckets =
                buckets.mapIndexed { index, (start, end) ->
                    HealthDayBucketResponse(
                        startAt = start.toString(),
                        endAt = end.toString(),
                        value = if (counts[index] == 0) null else totals[index] / counts[index],
                        count = counts[index],
                    )
                },
        )
    }

    private fun weight(context: HealthDayQueryContext): HealthDayWeightResponse {
        val filters = context.filters()
        val metricTypes = setOf(BodyMetricTypes.WEIGHT)
        val (points, pointSourceMetadata) = scalarRepository.list(filters, metricTypes, canonical = true)
        val (previous, previousSourceMetadata) = scalarRepository.latestBefore(filters, metricTypes, canonical = true)
        val latest = points.lastOrNull()
        val sourceMetadata = pointSourceMetadata + previousSourceMetadata

        return HealthDayWeightResponse(
            latest = latest?.toScalarResponse(sourceMetadata),
            previous = previous?.toScalarResponse(sourceMetadata),
            delta = if (latest != null && previous != null) latest.value - previous.value else null,
            points = points.map { it.toScalarResponse(sourceMetadata) },
        )
    }

    private fun sleep(context: HealthDayQueryContext): HealthDaySleepResponse {
        val filters =
            ReadFilters(
                fromDate = context.date,
                toDate = context.date.plusDays(1),
                timezone = context.timezone,
                provider = context.provider,
                providerInstanceId = context.providerInstanceId,
                includeSource = context.includeSource,
                limit = Int.MAX_VALUE,
                order = SortDirection.Asc,
            )
        val (nights, stagesBySession, sourceMetadata) =
            sleepRepository.listCanonicalSleepNights(filters)
        val sessions =
            nights
                .filter { night ->
                    night.session.startAt.isBefore(context.to) &&
                        night.session.endAt.isAfter(context.from)
                }.groupBy { it.date }
                .flatMap { (_, nightsForDate) ->
                    // The canonical sleep sessions can yield >1 row for a night when providers
                    // tie on rank (e.g. two unranked providers both at rank 10000). Keep a single
                    // source per night so two providers' overlapping stage segments aren't summed
                    // twice; multiple sessions from that one source (a fragmented night) still count.
                    val winningSource = nightsForDate.minOf { it.session.sourceInstanceId }
                    nightsForDate.filter { it.session.sourceInstanceId == winningSource }
                }.map { it.session }
        val segments =
            sessions
                .flatMap { session ->
                    stagesBySession[session.id].orEmpty().mapNotNull { stage ->
                        val start = maxOf(stage.startAt, context.from)
                        val end = minOf(stage.endAt, context.to)
                        if (start.isBefore(end)) SleepStageSegment(stage.stage, start, end) else null
                    }
                }.sortedBy { it.startAt }
        val stageTotals =
            segments
                .groupingBy { it.stage }
                .fold(0L) { total, segment ->
                    total + Duration.between(segment.startAt, segment.endAt).seconds
                }.map { (stage, duration) ->
                    HealthDaySleepStageTotalResponse(stage, duration)
                }.sortedBy { it.stage }
        val unstagedSeconds =
            sessions
                .filter { stagesBySession[it.id].isNullOrEmpty() }
                .sumOf { Duration.between(maxOf(it.startAt, context.from), minOf(it.endAt, context.to)).seconds }

        return HealthDaySleepResponse(
            totalDurationSeconds = stageTotals.sumOf { it.durationSeconds } + unstagedSeconds,
            sessions = sessions.map { it.toResponse(stagesBySession, sourceMetadata) },
            stageTotals = stageTotals,
            timeline =
                segments.map {
                    HealthDaySleepStageSegmentResponse(
                        stage = it.stage,
                        startAt = it.startAt.toString(),
                        endAt = it.endAt.toString(),
                    )
                },
        )
    }
}

/** A sleep stage clipped to the requested day, before it is formatted for the response. */
private data class SleepStageSegment(
    val stage: String,
    val startAt: Instant,
    val endAt: Instant,
)

private fun HealthDayQueryContext.filters(): ReadFilters =
    ReadFilters(
        from = from,
        to = to,
        provider = provider,
        providerInstanceId = providerInstanceId,
        includeSource = includeSource,
        limit = Int.MAX_VALUE,
        order = SortDirection.Asc,
    )

private val BUCKET_SIZE = Duration.ofMinutes(15)

private fun HealthDayQueryContext.buckets(): List<Pair<Instant, Instant>> =
    generateSequence(from) { it.plus(BUCKET_SIZE) }
        .takeWhile { it < to }
        .map { it to minOf(it.plus(BUCKET_SIZE), to) }
        .toList()

// The scale SQL AVG yields through Exposed, which the scalar summary endpoints report.
private fun Double.toTwoDecimals(): Double = BigDecimal(toString()).setScale(2, RoundingMode.HALF_EVEN).toDouble()
