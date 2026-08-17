package me.aquitano.health.application

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.aquitano.health.api.dto.ProviderSyncJobItemResponse
import me.aquitano.health.api.dto.ProviderSyncJobStartResponse
import me.aquitano.health.api.dto.ProviderSyncJobStatusResponse
import me.aquitano.health.api.dto.ProviderSyncRequest
import me.aquitano.health.api.dto.ProviderSyncResponse
import me.aquitano.health.domain.SyncJobStatus
import me.aquitano.health.application.providersync.ProviderSyncItem
import me.aquitano.health.application.providersync.ProviderSyncProgressSink
import me.aquitano.health.domain.ConflictException
import me.aquitano.health.domain.NotFoundException
import me.aquitano.health.domain.ProviderSyncRequest as DomainProviderSyncRequest
import me.aquitano.health.infrastructure.repositories.ProviderSyncJobRecord
import me.aquitano.health.infrastructure.repositories.ProviderSyncJobRepository
import me.aquitano.health.shared.AppJson
import io.github.oshai.kotlinlogging.KotlinLogging
import me.aquitano.health.infrastructure.logging.*
import java.time.Instant
import java.util.UUID

private val providerSyncJobLogger = KotlinLogging.logger {}

class ProviderSyncJobService(
    private val providerRegistry: HealthProviderRegistry,
    private val workflowService: ProviderWorkflowService,
    private val repository: ProviderSyncJobRepository,
    private val clock: me.aquitano.health.infrastructure.time.UtcClock,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /**
     * Resumes jobs interrupted by a restart. Re-running a job from the start is safe because
     * ingestion writes dedupe by provider record id; already-synced windows only cost a
     * provider re-fetch. Jobs restarted [MAX_JOB_RESTARTS] times are failed to stop crash loops.
     */
    fun start(now: Instant) {
        scope.launch {
            val requeued = repository.requeueInterruptedJobs(now, MAX_JOB_RESTARTS)
            requeued.abandoned.forEach { job ->
                providerSyncJobLogger.warnWithContext(
                    "provider_sync_job_abandoned",
                    "provider" to job.providerCode,
                    "jobId" to job.id,
                    "restartCount" to job.restartCount,
                )
            }
            requeued.resumed.forEach { job ->
                providerSyncJobLogger.infoWithContext(
                    "provider_sync_job_resumed",
                    "provider" to job.providerCode,
                    "jobId" to job.id,
                    "restartCount" to job.restartCount,
                )
                scope.launch {
                    runJob(job.id, job.providerCode, job.toDomainRequest())
                }
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    suspend fun create(
        providerCode: String,
        request: ProviderSyncRequest,
        now: Instant,
        idempotencyKey: String? = null,
    ): ProviderSyncJobStartResponse {
        val provider = providerRegistry.getProvider(providerCode)
            ?: throw NotFoundException("Provider '$providerCode' not found")
        val domainRequest = workflowService.toDomainSyncRequest(request, now)
        val requestHash = syncRequestHash(request)
        if (idempotencyKey != null) {
            repository.findByIdempotencyKey(provider.providerCode, idempotencyKey)
                ?.let { existing ->
                    existing.requireMatchingIdempotencyRequest(requestHash)
                    providerSyncJobLogger.infoWithContext(
                        "provider_sync_job_idempotent_replay",
                        "provider" to provider.providerCode,
                        "jobId" to existing.id,
                    )
                    return existing.toStartDto()
                }
        }
        val result = repository.create(
            id = UUID.randomUUID().toString(),
            providerCode = provider.providerCode,
            providerInstanceId = domainRequest.providerInstanceId,
            requestedFrom = domainRequest.from,
            requestedTo = domainRequest.to,
            dataTypes = domainRequest.dataTypes,
            pageSize = domainRequest.pageSize,
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
                runJob(job.id, provider.providerCode, domainRequest)
            }
        } else {
            providerSyncJobLogger.infoWithContext(
                "provider_sync_job_idempotent_replay",
                "provider" to provider.providerCode,
                "jobId" to job.id,
            )
        }

        return job.toStartDto()
    }

    suspend fun get(jobId: String): ProviderSyncJobStatusResponse =
        repository.get(jobId)?.toDto()
            ?: throw NotFoundException("Provider sync job '$jobId' not found")

    suspend fun latest(providerCode: String?): ProviderSyncJobStatusResponse? {
        val canonicalProviderCode = providerCode
            ?.let { providerRegistry.getProvider(it)?.providerCode }
            ?: providerCode
        return repository.latest(canonicalProviderCode)?.toDto()
    }

    private suspend fun runJob(
        jobId: String,
        providerCode: String,
        request: DomainProviderSyncRequest,
    ) {
        repository.markRunning(jobId, clock.now())
        providerSyncJobLogger.infoWithContext(
            "provider_sync_job_started",
            "provider" to providerCode,
            "jobId" to jobId,
        )

        try {
            val summary = workflowService.sync(
                providerCode = providerCode,
                request = request,
                now = clock.now(),
                progress = JobProgressSink(jobId, repository, clock),
            )
            repository.finish(
                id = jobId,
                status = summary.status.stored,
                batchesCount = summary.batches.size,
                emptyCount = summary.emptyDataTypes.size,
                errorCount = summary.errors.size,
                summaryJson = AppJson.encodeToString(summary),
                errorMessage = summary.errors.joinToString("; ") { "${it.dataType}: ${it.message}" }
                    .ifBlank { null },
                now = clock.now(),
            )
            providerSyncJobLogger.infoWithContext(
                "provider_sync_job_completed",
                "provider" to providerCode,
                "jobId" to jobId,
                "status" to summary.status,
            )
        } catch (exception: Exception) {
            repository.finish(
                id = jobId,
                status = "failed",
                batchesCount = 0,
                emptyCount = 0,
                errorCount = 1,
                summaryJson = null,
                errorMessage = exception.message ?: "Provider sync failed.",
                now = clock.now(),
            )
            providerSyncJobLogger.warnWithContext(
                "provider_sync_job_failed",
                "provider" to providerCode,
                "jobId" to jobId,
                throwable = exception,
            )
        }
    }

    private class JobProgressSink(
        private val jobId: String,
        private val repository: ProviderSyncJobRepository,
        private val clock: me.aquitano.health.infrastructure.time.UtcClock,
    ) : ProviderSyncProgressSink {
        override suspend fun started(totalItems: Int, providerInstanceId: String) {
            repository.markStarted(jobId, providerInstanceId, totalItems, clock.now())
        }

        override suspend fun itemStarted(item: ProviderSyncItem) {
            repository.markItemStarted(jobId, item.dataType, item.from, item.to, clock.now())
        }

        override suspend fun itemCompleted(item: ProviderSyncItem) {
            repository.markItemCompleted(jobId, item.dataType, item.from, item.to, clock.now())
        }
    }

    private fun ProviderSyncJobRecord.toStartDto(): ProviderSyncJobStartResponse =
        ProviderSyncJobStartResponse(
            jobId = id,
            status = SyncJobStatus.fromStored(status),
            createdAt = createdAt.toString(),
        )

    private fun ProviderSyncJobRecord.requireMatchingIdempotencyRequest(requestHash: String) {
        if (idempotencyRequestHash == requestHash) return
        throw ConflictException(
            "idempotency_key_conflict",
            "Idempotency-Key was already used for a different provider sync request.",
        )
    }

    /**
     * provider_sync_jobs stores the internal provider code so it correlates with scheduled_syncs
     * and provider_sync_runs; API responses keep returning the hyphenated wire code.
     */
    private fun wireProviderCode(providerCode: String): String =
        providerRegistry.getProviderDescriptor(providerCode)?.providerCode ?: providerCode

    private fun ProviderSyncJobRecord.toDto(): ProviderSyncJobStatusResponse =
        ProviderSyncJobStatusResponse(
            jobId = id,
            providerCode = wireProviderCode(providerCode),
            providerInstanceId = providerInstanceId,
            requestedFrom = requestedFrom.toString(),
            requestedTo = requestedTo.toString(),
            dataTypes = dataTypes,
            status = SyncJobStatus.fromStored(status),
            totalItems = totalItems,
            completedItems = completedItems,
            currentItem = itemDto(currentDataType, currentFrom, currentTo),
            lastCompletedItem = itemDto(lastCompletedDataType, lastCompletedFrom, lastCompletedTo),
            batchesCount = batchesCount,
            emptyCount = emptyCount,
            errorCount = errorCount,
            restartCount = restartCount,
            errorMessage = errorMessage,
            createdAt = createdAt.toString(),
            startedAt = startedAt?.toString(),
            updatedAt = updatedAt.toString(),
            finishedAt = finishedAt?.toString(),
            summary = summaryJson?.let {
                runCatching { AppJson.decodeFromString<ProviderSyncResponse>(it) }.getOrNull()
            },
        )

    private fun itemDto(
        dataType: String?,
        from: Instant?,
        to: Instant?,
    ): ProviderSyncJobItemResponse? =
        if (dataType == null || from == null || to == null) {
            null
        } else {
            ProviderSyncJobItemResponse(dataType, from.toString(), to.toString())
        }
}

private const val MAX_JOB_RESTARTS = 3

private fun ProviderSyncJobRecord.toDomainRequest(): DomainProviderSyncRequest =
    DomainProviderSyncRequest(
        providerInstanceId = providerInstanceId,
        from = requestedFrom,
        to = requestedTo,
        dataTypes = dataTypes,
        pageSize = pageSize,
    )

