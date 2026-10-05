package me.aquitano.health.domain

import java.time.Instant

interface HealthProvider {
    /** Stored spelling, e.g. `google_health`; [HealthProviderDescriptor.providerCode] is the wire spelling. */
    val providerCode: String
    val descriptor: HealthProviderDescriptor

    /** Instance id used until the provider can return an account-specific identifier. */
    val defaultProviderInstanceId: String

    fun isConfigured(): Boolean

    fun getAuthUrl(state: String): String

    /** Exchanges an authorization code and stores the resulting provider account. */
    suspend fun connect(
        code: String,
        now: Instant,
    ): ProviderConnection

    suspend fun sync(
        request: ProviderSyncRequest,
        now: Instant,
        progress: ProviderSyncProgressSink = ProviderSyncProgressSink.None,
    ): ProviderSyncSummary
}

data class HealthProviderDescriptor(
    val providerCode: String,
    val displayName: String,
    val supportedDataTypes: List<String>,
    val defaultDataTypes: List<String>,
    val maxSyncRangeDays: Int,
    val supportsPageSize: Boolean,
)

data class ProviderConnection(
    val providerCode: String,
    val providerInstanceId: String,
    val connected: Boolean,
)

data class ProviderSyncRequest(
    val providerInstanceId: String? = null,
    val from: Instant,
    val to: Instant,
    val dataTypes: List<String>? = null,
    val pageSize: Int? = null,
    // Scheduled lookbacks fetch completed windows again to collect late provider records.
    val refresh: Boolean = false,
)

data class ProviderSyncSummary(
    val providerCode: String,
    val providerInstanceId: String,
    val requestedFrom: Instant,
    val requestedTo: Instant,
    val status: SyncStatus,
    val batches: List<ProviderSyncBatch>,
    val errors: List<ProviderSyncError>,
    val emptyDataTypes: List<ProviderSyncEmptyDataType> = emptyList(),
)

data class ProviderSyncBatch(
    val dataType: String,
    val batchId: Int,
    val duplicateBatch: Boolean,
    val recordsReceived: Int,
    val ingestionRecordsStored: Int,
    val metricsCreated: MetricCreatedCounts,
    val duplicateMetricsSkipped: Int,
    val affectedStepSummaryDates: List<String>,
)

data class ProviderSyncError(
    val dataType: String,
    val code: String,
    val message: String,
    val retryable: Boolean = true,
)

data class ProviderSyncEmptyDataType(
    val dataType: String,
    val pagesFetched: Int,
    val sourceRecordsReceived: Int,
    val normalizedRecords: Int,
)
