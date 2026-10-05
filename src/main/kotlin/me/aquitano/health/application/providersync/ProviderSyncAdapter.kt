package me.aquitano.health.application.providersync

import me.aquitano.health.domain.ConflictException
import me.aquitano.health.domain.ProviderAccountStatus
import me.aquitano.health.domain.ProviderSyncItem
import me.aquitano.health.domain.ProviderSyncRequest
import me.aquitano.health.domain.RequestValidationException
import me.aquitano.health.domain.UpstreamProviderException
import me.aquitano.health.domain.ValidationIssue
import me.aquitano.health.domain.ValidationIssueCodes
import java.time.Duration
import java.time.Instant

/**
 * The provider-specific half of a sync. Error codes, client messages, batch ids, and the window
 * plan are derived from [providerCode] and [displayName] by the extensions below, so every
 * provider reports them in the same shape.
 */
interface ProviderSyncAdapter {
    val providerCode: String
    val displayName: String
    val dataTypes: List<String>
    val recordEmptyDataTypes: Boolean
        get() = false
    val providerRequestInterval: Duration
        get() = Duration.ZERO
    val tokenRefreshFailureMessage: String
        get() = "$displayName OAuth token refresh failed"

    suspend fun refreshAccessToken(
        refreshToken: String,
        now: Instant,
    ): RefreshedTokenSet

    suspend fun fetch(
        accessToken: String,
        item: ProviderSyncItem,
    ): ProviderFetchedBatch

    fun isUnauthorized(error: Throwable): Boolean

    fun isInvalidRefreshToken(error: Throwable): Boolean

    /** The code carried by the provider client's own exception type, if [error] is one. */
    fun providerErrorCode(error: Throwable): String?

    fun errorAttributes(error: Throwable): Map<String, String> = emptyMap()
}

internal val ProviderSyncAdapter.syncFailureMessage: String
    get() = "$displayName sync failed"

internal val ProviderSyncAdapter.tokenRefreshFailureCode: String
    get() = "${providerCode}_token_refresh_failed"

internal val ProviderSyncAdapter.needsReauthCode: String
    get() = "${providerCode}_needs_reauth"

internal fun ProviderSyncAdapter.needsReauth(cause: Throwable? = null): ConflictException = ConflictException(needsReauthCode, "$displayName needs reconnect before syncing", cause = cause)

internal fun ProviderSyncAdapter.accountUnavailable(
    providerInstanceId: String?,
    statusHint: SyncAccount?,
): ConflictException =
    when {
        statusHint?.accountStatus == ProviderAccountStatus.NeedsReauth -> {
            needsReauth()
        }

        providerInstanceId == null -> {
            ConflictException("${providerCode}_not_connected", "$displayName is not connected")
        }

        else -> {
            ConflictException(
                "${providerCode}_account_not_found",
                "$displayName account is not connected for providerInstanceId: $providerInstanceId",
            )
        }
    }

internal fun ProviderSyncAdapter.errorCode(error: Throwable): String =
    providerErrorCode(error)
        ?: (error as? UpstreamProviderException)?.code
        ?: "${providerCode}_sync_failed"

internal fun ProviderSyncAdapter.batchExternalId(
    providerInstanceId: String,
    item: ProviderSyncItem,
): String = "$providerCode:$providerInstanceId:${item.dataType}:${item.from}:${item.to}"

/** Validates the requested data types and splits the range into one item per data type and UTC day. */
internal fun ProviderSyncAdapter.plan(request: ProviderSyncRequest): List<ProviderSyncItem> {
    val requested = request.dataTypes?.takeIf { it.isNotEmpty() } ?: dataTypes
    val issues =
        requested.mapIndexedNotNull { index, dataType ->
            ValidationIssue(
                field = "dataTypes[$index]",
                code = ValidationIssueCodes.UnsupportedValue,
                message = "unsupported $displayName data type",
            ).takeIf { dataType !in dataTypes }
        }
    if (issues.isNotEmpty()) throw RequestValidationException(issues)

    val windows = dailySyncWindows(request.from, request.to)
    return requested.distinct().flatMap { dataType ->
        windows.map { window -> ProviderSyncItem(dataType, window.from, window.to, request.pageSize) }
    }
}
