package me.aquitano.health.application

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.aquitano.health.api.dto.IngestionRecord
import me.aquitano.health.api.dto.ReplayJobStartResponse
import me.aquitano.health.api.dto.ReplayJobStatusResponse
import me.aquitano.health.api.dto.ReplayRequest
import me.aquitano.health.application.metric.common.MetricWrite
import me.aquitano.health.application.metric.common.MetricWriteService
import me.aquitano.health.domain.ConflictException
import me.aquitano.health.domain.NotFoundException
import me.aquitano.health.domain.RecordTypes
import me.aquitano.health.domain.ReplayJobStatus
import me.aquitano.health.domain.ReplayScope
import me.aquitano.health.domain.RequestValidationException
import me.aquitano.health.domain.ValidationIssue
import me.aquitano.health.domain.ValidationIssueCodes
import me.aquitano.health.infrastructure.database.suspendDbTransaction
import me.aquitano.health.infrastructure.logging.infoWithContext
import me.aquitano.health.infrastructure.logging.warnWithContext
import me.aquitano.health.infrastructure.repositories.IngestionRepository
import me.aquitano.health.infrastructure.repositories.PendingDerivedRebuildRecord
import me.aquitano.health.infrastructure.repositories.PendingDerivedRebuildRepository
import me.aquitano.health.infrastructure.repositories.ProjectionWipeRepository
import me.aquitano.health.infrastructure.repositories.ReplayJobRecord
import me.aquitano.health.infrastructure.repositories.ReplayJobRepository
import me.aquitano.health.infrastructure.repositories.ReplayRecordRow
import me.aquitano.health.shared.AppJson
import me.aquitano.health.shared.utcDate
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CancellationException

private val replayLogger = KotlinLogging.logger {}

private val replayableRecordTypes =
    setOf(
        RecordTypes.STEP_INTERVAL,
        RecordTypes.SLEEP_SESSION,
        RecordTypes.ACTIVITY_SUMMARY,
        RecordTypes.SLEEP_SUMMARY,
        RecordTypes.BLOOD_PRESSURE,
        RecordTypes.SCALAR,
    )

/**
 * Rebuilds metric projections from the raw event log (ingestion_records) for any date range.
 * The post-ingestion incremental derived rebuild is the special case of this operation that
 * runs for the dates touched by a single batch.
 */
class ReplayService(
    private val database: Database,
    private val ingestionRepository: IngestionRepository,
    private val mappingService: IngestionMappingService,
    private val metricWriteService: MetricWriteService,
    private val derivedRebuildExecutor: DerivedRebuildExecutor,
    private val derivedRebuildRegistry: DerivedRebuildModuleRegistry,
    private val pendingDerivedRebuildRepository: PendingDerivedRebuildRepository,
    private val replayJobRepository: ReplayJobRepository,
    private val projectionWipeRepository: ProjectionWipeRepository,
    private val clock: Clock,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    fun start(now: Instant) {
        scope.launch {
            replayJobRepository.markInterruptedUnfinishedJobs(now)
        }
    }

    fun stop() {
        scope.cancel()
    }

    suspend fun create(
        request: ReplayRequest,
        now: Instant,
        idempotencyKey: String? = null,
    ): ReplayJobStartResponse {
        val plan = validate(request)
        val requestHash = plan.idempotencyRequestHash()
        if (idempotencyKey != null) {
            replayJobRepository.findByIdempotencyKey(idempotencyKey)?.let { existing ->
                existing.requireMatchingIdempotencyRequest(requestHash)
                replayLogger.infoWithContext(
                    "replay_job_idempotent_replay",
                    "jobId" to existing.id,
                )
                return existing.toStartDto()
            }
        }
        val result =
            replayJobRepository.create(
                id = UUID.randomUUID().toString(),
                scope = plan.scope,
                metricTypes = plan.recordTypes?.toList(),
                fromDate = plan.fromDate,
                toDate = plan.toDate,
                wipe = plan.wipe,
                now = now,
                idempotencyKey = idempotencyKey,
                idempotencyRequestHash = idempotencyKey?.let { requestHash },
            )
        val job = result.record
        if (idempotencyKey != null) {
            job.requireMatchingIdempotencyRequest(requestHash)
        }
        if (result.created) {
            scope.launch {
                runJob(job.id, plan)
            }
        } else {
            replayLogger.infoWithContext(
                "replay_job_idempotent_replay",
                "jobId" to job.id,
            )
        }

        return job.toStartDto()
    }

    private fun ReplayJobRecord.toStartDto(): ReplayJobStartResponse =
        ReplayJobStartResponse(
            jobId = id,
            status = status,
            createdAt = createdAt.toString(),
        )

    private fun ReplayJobRecord.requireMatchingIdempotencyRequest(requestHash: String) {
        if (idempotencyRequestHash == requestHash) return
        throw ConflictException(
            "idempotency_key_conflict",
            "Idempotency-Key was already used for a different replay request.",
        )
    }

    suspend fun get(jobId: String): ReplayJobStatusResponse =
        replayJobRepository.get(jobId)?.toDto()
            ?: throw NotFoundException("Replay job '$jobId' not found")

    suspend fun latest(): ReplayJobStatusResponse? = replayJobRepository.latest()?.toDto()

    private suspend fun runJob(
        jobId: String,
        plan: ReplayPlan,
    ) {
        try {
            val days = planDays(plan)
            replayJobRepository.markRunning(jobId, days.size, clock.instant())
            replayLogger.infoWithContext(
                "replay_job_started",
                "jobId" to jobId,
                "scope" to plan.scope.stored,
                "days" to days.size,
                "wipe" to plan.wipe,
            )

            days.forEach { day ->
                replayJobRepository.markItemStarted(jobId, day.toString(), clock.instant())
                val result = replayDay(day, plan)
                replayJobRepository.markItemCompleted(
                    id = jobId,
                    recordsReplayed = result.recordsReplayed,
                    metricsWritten = result.metricsWritten,
                    duplicatesSkipped = result.duplicatesSkipped,
                    mappingFailures = result.mappingFailures,
                    now = clock.instant(),
                )
            }

            replayJobRepository.finish(jobId, ReplayJobStatus.Completed, null, clock.instant())
            replayLogger.infoWithContext(
                "replay_job_completed",
                "jobId" to jobId,
                "days" to days.size,
            )
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            replayJobRepository.finish(
                jobId,
                ReplayJobStatus.Failed,
                exception.message ?: "Replay failed.",
                clock.instant(),
            )
            replayLogger.warnWithContext(
                "replay_job_failed",
                "jobId" to jobId,
                throwable = exception,
            )
        }
    }

    private suspend fun planDays(plan: ReplayPlan): List<LocalDate> {
        val bounds =
            suspendDbTransaction(db = database) {
                ingestionRepository.replayDateBounds(plan.recordTypes)
            } ?: return emptyList()
        val firstDay = maxOf(bounds.first.utcDate(), plan.fromDate ?: bounds.first.utcDate())
        val lastDay = minOf(bounds.second.utcDate(), plan.toDate ?: bounds.second.utcDate())
        if (firstDay.isAfter(lastDay)) return emptyList()
        return generateSequence(firstDay) { it.plusDays(1) }
            .takeWhile { !it.isAfter(lastDay) }
            .toList()
    }

    private suspend fun replayDay(
        day: LocalDate,
        plan: ReplayPlan,
    ): DayReplayResult {
        repeat(3) {
            tryReplayDay(day, plan)?.let { return it }
        }
        throw ConflictException("replay_source_changed", "Ingestion changed repeatedly while preparing replay for $day. Retry the replay.")
    }

    private suspend fun tryReplayDay(
        day: LocalDate,
        plan: ReplayPlan,
    ): DayReplayResult? {
        val dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant()
        val dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val now = clock.instant()
        val affectedBySource = mutableMapOf<Int, MutableSet<LocalDate>>()

        val rows =
            suspendDbTransaction(db = database) {
                ingestionRepository.listRecordsForReplay(dayStart, dayEnd, plan.recordTypes)
            }
        val prepared =
            if (plan.scope.includesProjections) {
                rows.mapNotNull { row ->
                    decodeAndMap(row)?.let { record -> row to MetricWrite(row.id, record) }
                }
            } else {
                emptyList()
            }
        val writesBySource = prepared.groupBy { it.first.sourceInstanceId }.toSortedMap()
        if (plan.scope.includesDerived) {
            rows.forEach { row ->
                val dates = derivedRebuildRegistry.affectedDatesFor(row.recordType, row.recordStartAt, row.recordEndAt)
                if (dates.isNotEmpty()) {
                    affectedBySource.getOrPut(row.sourceInstanceId) { linkedSetOf() }.addAll(dates)
                }
            }
        }
        val replayed =
            suspendDbTransaction(db = database) {
                if (plan.scope.includesProjections && plan.wipe) {
                    // Ingestion takes a write lock here before touching projections. Taking the
                    // conflicting lock first waits for commits and prevents new writes during wipe.
                    exec("LOCK TABLE ingestion_records IN SHARE ROW EXCLUSIVE MODE")
                    val recordIds = rows.mapTo(hashSetOf()) { it.id }
                    if (ingestionRepository.recordIdsForReplay(dayStart, dayEnd, plan.recordTypes) != recordIds) {
                        return@suspendDbTransaction null
                    }
                }
                var recordsReplayed = 0
                var metricsWritten = 0
                var duplicatesSkipped = 0
                val mappingFailures = if (plan.scope.includesProjections) rows.size - prepared.size else 0

                if (plan.scope.includesProjections) {
                    if (plan.wipe) {
                        projectionWipeRepository.wipeDay(
                            day = day,
                            dayStart = dayStart,
                            dayEnd = dayEnd,
                            recordTypes = plan.recordTypes ?: replayableRecordTypes,
                        )
                    }
                    writesBySource.forEach { (sourceId, entries) ->
                        val writeResult =
                            metricWriteService.writeAll(
                                provider = entries.first().first.provider,
                                sourceInstanceId = sourceId,
                                writes = entries.map { it.second },
                                now = now,
                            )
                        if (plan.scope.includesDerived) {
                            if (writeResult.affectedStepDates.isNotEmpty()) {
                                affectedBySource.getOrPut(sourceId) { linkedSetOf() }.addAll(writeResult.affectedStepDates)
                            }
                        }
                        recordsReplayed += entries.size
                        metricsWritten +=
                            writeResult.created.counts.values
                                .sum()
                        duplicatesSkipped += writeResult.duplicateSkipped
                    }
                }

                val rebuildRequests =
                    affectedBySource.map { (sourceInstanceId, dates) ->
                        DerivedRebuildRequest(sourceInstanceId, dates.toSet())
                    }
                ReplayedDay(
                    result = DayReplayResult(recordsReplayed, metricsWritten, duplicatesSkipped, mappingFailures),
                    rebuildRequests = rebuildRequests,
                    queuedRebuilds = rebuildRequests.flatMap { pendingDerivedRebuildRepository.enqueueInTransaction(it, now) },
                )
            } ?: return null

        derivedRebuildExecutor.rebuild(replayed.rebuildRequests, clock.instant())
        pendingDerivedRebuildRepository.deleteCompleted(replayed.queuedRebuilds)

        return replayed.result
    }

    private fun decodeAndMap(row: ReplayRecordRow) =
        runCatching {
            AppJson.decodeFromString(IngestionRecord.serializer(), row.normalizedRecordJson)
        }.getOrElse { exception ->
            replayLogger.warnWithContext(
                "replay_record_decode_failed",
                "ingestionRecordId" to row.id,
                "recordType" to row.recordType,
                throwable = exception,
            )
            null
        }?.let { dto ->
            mappingService.mapRecord(dto).also { record ->
                if (record == null) {
                    replayLogger.warnWithContext(
                        "replay_record_mapping_failed",
                        "ingestionRecordId" to row.id,
                        "recordType" to row.recordType,
                    )
                }
            }
        }

    private fun validate(request: ReplayRequest): ReplayPlan {
        val issues = mutableListOf<ValidationIssue>()

        val recordTypes = request.metricTypes?.toSet()
        recordTypes?.minus(replayableRecordTypes)?.mapTo(issues) { unknown ->
            ValidationIssue(
                field = "metricTypes",
                code = ValidationIssueCodes.UnsupportedValue,
                message = "unsupported record type '$unknown'",
            )
        }

        val fromDate = request.fromDate?.let { parseDate(it, "fromDate", issues) }
        val toDate = request.toDate?.let { parseDate(it, "toDate", issues) }
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            issues.add(
                ValidationIssue(
                    field = "fromDate",
                    code = ValidationIssueCodes.InvalidRange,
                    message = "must not be after toDate",
                ),
            )
        }
        if (request.wipe && !request.scope.includesProjections) {
            issues.add(
                ValidationIssue(
                    field = "wipe",
                    code = ValidationIssueCodes.InvalidState,
                    message = "wipe requires the projections stage",
                ),
            )
        }

        if (issues.isNotEmpty()) throw RequestValidationException(issues)

        return ReplayPlan(
            scope = request.scope,
            recordTypes = recordTypes,
            fromDate = fromDate,
            toDate = toDate,
            wipe = request.wipe,
        )
    }

    private fun ReplayJobRecord.toDto(): ReplayJobStatusResponse =
        ReplayJobStatusResponse(
            jobId = id,
            scope = scope,
            metricTypes = metricTypes,
            fromDate = fromDate?.toString(),
            toDate = toDate?.toString(),
            wipe = wipe,
            status = status,
            totalItems = totalItems,
            completedItems = completedItems,
            currentItem = currentItem,
            recordsReplayed = recordsReplayed,
            metricsWritten = metricsWritten,
            duplicatesSkipped = duplicatesSkipped,
            mappingFailures = mappingFailures,
            errorMessage = errorMessage,
            createdAt = createdAt.toString(),
            startedAt = startedAt?.toString(),
            updatedAt = updatedAt.toString(),
            finishedAt = finishedAt?.toString(),
        )
}

private fun ReplayPlan.idempotencyRequestHash(): String =
    idempotencyRequestHash(
        scope.stored,
        recordTypes?.idempotencyListPart(),
        fromDate?.toString(),
        toDate?.toString(),
        wipe.toString(),
    )

private data class ReplayPlan(
    val scope: ReplayScope,
    val recordTypes: Set<String>?,
    val fromDate: LocalDate?,
    val toDate: LocalDate?,
    val wipe: Boolean,
)

private data class DayReplayResult(
    val recordsReplayed: Int,
    val metricsWritten: Int,
    val duplicatesSkipped: Int,
    val mappingFailures: Int,
)

private data class ReplayedDay(
    val result: DayReplayResult,
    val rebuildRequests: List<DerivedRebuildRequest>,
    val queuedRebuilds: List<PendingDerivedRebuildRecord>,
)
