package me.aquitano.health.application

import me.aquitano.health.api.dto.*
import me.aquitano.health.application.metric.common.QueryParams
import me.aquitano.health.application.metric.common.repository.ReadFilters
import me.aquitano.health.application.metric.common.singleSource
import me.aquitano.health.application.metric.common.toResponse
import me.aquitano.health.application.metric.scalar.ScalarSampleReadRepository
import me.aquitano.health.application.metric.scalar.ScalarSampleRow
import me.aquitano.health.application.metric.scalar.toScalarResponse
import me.aquitano.health.application.metric.sleep.repository.SleepRepository
import me.aquitano.health.application.metric.steps.derived.CANONICAL_STEP_ALGORITHM_VERSION
import me.aquitano.health.application.metric.steps.repository.CanonicalStepDerivationRepository
import me.aquitano.health.domain.BodyMetricTypes
import me.aquitano.health.domain.RequestValidationException
import me.aquitano.health.domain.ScalarMetricTypes
import me.aquitano.health.domain.ValidationIssue
import me.aquitano.health.domain.ValidationIssueCodes
import me.aquitano.health.infrastructure.database.suspendDbTransaction
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

data class HealthDayQueryContext(
    val date: LocalDate,
    val timezone: ZoneId,
    val from: Instant,
    val to: Instant,
    val provider: String?,
    val providerInstanceId: String?,
    val includeSource: Boolean,
    val computedAt: Instant,
)

interface HealthDayModule<T> {
    val name: HealthDayModuleName

    suspend fun read(context: HealthDayQueryContext): T

    fun apply(
        response: HealthDayResponse,
        result: T,
    ): HealthDayResponse

    suspend fun appendTo(
        context: HealthDayQueryContext,
        response: HealthDayResponse,
    ): HealthDayResponse = apply(response, read(context))
}

class HealthDayModuleRegistry(
    modules: List<HealthDayModule<*>>,
) {
    private val byName = modules.associateBy { it.name }

    init {
        require(byName.size == modules.size) { "Duplicate health-day modules" }
        require(byName.keys == HealthDayModuleName.entries.toSet()) { "Missing health-day modules" }
    }

    fun resolve(names: List<HealthDayModuleName>): List<HealthDayModule<*>> = names.map { byName.getValue(it) }
}

class HealthDayQueryService(
    private val database: Database,
    private val registry: HealthDayModuleRegistry,
) {
    suspend fun getHealthDay(
        params: QueryParams,
        now: Instant,
    ): HealthDayResponse {
        val timezone = params.timezone()
        val date =
            params.dateOrToday("date", now, timezone)
                ?: throw RequestValidationException(field = "date", code = ValidationIssueCodes.Required, message = "is required")
        val moduleNames = parseModules(params.required("modules"))
        val modules = registry.resolve(moduleNames)
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
                computedAt = now,
            )

        return suspendDbTransaction(db = database) {
            var response =
                HealthDayResponse(
                    date = date.toString(),
                    timezone = timezone.id,
                    from = from.toString(),
                    to = to.toString(),
                    modules = moduleNames,
                    steps = null,
                    heartRate = null,
                    weight = null,
                    sleep = null,
                )
            modules.forEach { response = it.appendTo(context, response) }
            response
        }
    }

    private fun parseModules(value: String): List<HealthDayModuleName> {
        val modules =
            value
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        if (modules.isEmpty()) {
            throw RequestValidationException(field = "modules", code = ValidationIssueCodes.Required, message = "must contain at least one module")
        }
        val unsupported = modules.filter { HealthDayModuleName.fromWireName(it) == null }
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
        return modules.map { HealthDayModuleName.fromWireName(it)!! }
    }
}

class StepsDayModule(
    private val canonicalRepository: CanonicalStepDerivationRepository,
) : HealthDayModule<HealthDayStepsResponse> {
    override val name = HealthDayModuleName.Steps

    override fun apply(
        response: HealthDayResponse,
        result: HealthDayStepsResponse,
    ) = response.copy(steps = result)

    override suspend fun read(context: HealthDayQueryContext): HealthDayStepsResponse {
        val filters = context.filters()
        val (rows, sourceMetadata) =
            canonicalRepository.listCanonicalStepSamples(
                filters,
                CANONICAL_STEP_ALGORITHM_VERSION,
                overlapsWindow = true,
            )
        val buckets = buckets(context)
        val values = DoubleArray(buckets.size)
        val counts = IntArray(buckets.size)

        val byStart = buckets.mapIndexed { index, bucket -> bucket.first to index }.toMap()
        canonicalRepository
            .listBucketContributions(filters, CANONICAL_STEP_ALGORITHM_VERSION)
            .forEach { contribution ->
                val index = byStart[contribution.bucketStartAt]
                if (index != null) {
                    values[index] += contribution.value
                    counts[index] += 1
                }
            }

        return HealthDayStepsResponse(
            total = values.sum().roundToInt(),
            sampleCount = rows.size,
            buckets =
                buckets.mapIndexed { index, (start, end) ->
                    HealthDayBucketResponse(
                        startAt = start.toString(),
                        endAt = end.toString(),
                        value = if (counts[index] == 0) null else values[index],
                        count = counts[index],
                    )
                },
            source = rows.singleSource(sourceMetadata) { it.sourceInstanceId },
        )
    }
}

class HeartRateDayModule(
    private val scalarRepository: ScalarSampleReadRepository,
) : HealthDayModule<HealthDayHeartRateResponse> {
    override val name = HealthDayModuleName.HeartRate

    override fun apply(
        response: HealthDayResponse,
        result: HealthDayHeartRateResponse,
    ) = response.copy(heartRate = result)

    private val metricTypes = setOf(ScalarMetricTypes.HEART_RATE)

    override suspend fun read(context: HealthDayQueryContext): HealthDayHeartRateResponse {
        val filters = context.filters()
        val (samples, sourceMetadata) =
            scalarRepository.list(
                filters.copy(limit = Int.MAX_VALUE, order = "asc"),
                metricTypes,
                canonical = true,
            )
        val summary = scalarRepository.summarize(filters, metricTypes, canonical = true)
        val latest = samples.maxWithOrNull(compareBy<ScalarSampleRow> { it.measuredAt }.thenBy { it.id })
        val buckets = buckets(context)
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
            count = summary.count,
            minBpm = summary.minValue?.roundToInt(),
            maxBpm = summary.maxValue?.roundToInt(),
            avgBpm = summary.avgValue,
            latest = latest?.toScalarResponse(sourceMetadata),
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
}

class WeightDayModule(
    private val scalarRepository: ScalarSampleReadRepository,
) : HealthDayModule<HealthDayWeightResponse> {
    override val name = HealthDayModuleName.Weight

    override fun apply(
        response: HealthDayResponse,
        result: HealthDayWeightResponse,
    ) = response.copy(weight = result)

    private val metricTypes = setOf(BodyMetricTypes.WEIGHT)

    override suspend fun read(context: HealthDayQueryContext): HealthDayWeightResponse {
        val filters = context.filters()
        val (points, pointSourceMetadata) = scalarRepository.list(filters, metricTypes, canonical = true)
        val (previous, previousSourceMetadata) =
            scalarRepository.latestBefore(filters, metricTypes, canonical = true)
        val latest = points.maxWithOrNull(compareBy<ScalarSampleRow> { it.measuredAt }.thenBy { it.id })
        val sourceMetadata = pointSourceMetadata + previousSourceMetadata

        return HealthDayWeightResponse(
            latest = latest?.toScalarResponse(sourceMetadata),
            previous = previous?.toScalarResponse(sourceMetadata),
            delta = if (latest != null && previous != null) latest.value - previous.value else null,
            points = points.map { it.toScalarResponse(sourceMetadata) },
        )
    }
}

class SleepDayModule(
    private val sleepRepository: SleepRepository,
) : HealthDayModule<HealthDaySleepResponse> {
    override val name = HealthDayModuleName.Sleep

    override fun apply(
        response: HealthDayResponse,
        result: HealthDaySleepResponse,
    ) = response.copy(sleep = result)

    override suspend fun read(context: HealthDayQueryContext): HealthDaySleepResponse {
        val filters =
            ReadFilters(
                fromDate = context.date,
                toDate = context.date.plusDays(1),
                timezone = context.timezone,
                provider = context.provider,
                providerInstanceId = context.providerInstanceId,
                includeSource = context.includeSource,
                limit = Int.MAX_VALUE,
                order = "asc",
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
        order = "asc",
    )

private fun buckets(context: HealthDayQueryContext): List<Pair<Instant, Instant>> {
    val result = mutableListOf<Pair<Instant, Instant>>()
    var start = context.from
    while (start.isBefore(context.to)) {
        val end = minOf(start.plus(Duration.ofMinutes(15)), context.to)
        result += start to end
        start = end
    }
    return result
}
