package me.aquitano.health.application

import me.aquitano.health.api.dto.ProviderSyncBatchResponse
import me.aquitano.health.api.dto.ProviderSyncEmptyDataTypeResponse
import me.aquitano.health.api.dto.ProviderSyncErrorResponse
import me.aquitano.health.api.dto.ProviderSyncResponse
import me.aquitano.health.domain.ProviderSyncBatch
import me.aquitano.health.domain.ProviderSyncEmptyDataType
import me.aquitano.health.domain.ProviderSyncError
import me.aquitano.health.domain.ProviderSyncSummary
import me.aquitano.health.domain.SyncStatus

internal fun ProviderSyncSummary.toDto(): ProviderSyncResponse =
    ProviderSyncResponse(
        providerCode = providerCode,
        providerInstanceId = providerInstanceId,
        requestedFrom = requestedFrom.toString(),
        requestedTo = requestedTo.toString(),
        status = SyncStatus.fromStored(status),
        batches = batches.map { it.toDto() },
        emptyDataTypes = emptyDataTypes.map { it.toDto() },
        errors = errors.map { it.toDto() },
    )

internal fun ProviderSyncBatch.toDto(): ProviderSyncBatchResponse =
    ProviderSyncBatchResponse(
        dataType = dataType,
        batchId = batchId,
        duplicateBatch = duplicateBatch,
        recordsReceived = recordsReceived,
        ingestionRecordsStored = ingestionRecordsStored,
        metricsCreated = metricsCreated.counts,
        duplicateMetricsSkipped = duplicateMetricsSkipped,
        affectedStepSummaryDates = affectedStepSummaryDates,
    )

internal fun ProviderSyncError.toDto(): ProviderSyncErrorResponse =
    ProviderSyncErrorResponse(
        dataType = dataType,
        code = code,
        message = message,
    )

internal fun ProviderSyncEmptyDataType.toDto(): ProviderSyncEmptyDataTypeResponse =
    ProviderSyncEmptyDataTypeResponse(
        dataType = dataType,
        pagesFetched = pagesFetched,
        sourceRecordsReceived = sourceRecordsReceived,
        normalizedRecords = normalizedRecords,
    )
