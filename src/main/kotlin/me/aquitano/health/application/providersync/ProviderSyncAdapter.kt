package me.aquitano.health.application.providersync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.aquitano.health.domain.ProviderSyncRequest
import java.time.Duration
import java.time.Instant

interface ProviderSyncAdapter {
    val providerCode: String

    /** Keys copied from the fetched batch's payload into the stored source payload. */
    val passthroughPayloadKeys: List<String>
        get() = listOf("pages")
    val defaultSyncFailureMessage: String
    val tokenRefreshFailureCode: String
    val tokenRefreshFailureMessage: String
    val needsReauthCode: String
    val needsReauthMessage: String
    val recordEmptyDataTypes: Boolean
        get() = false
    val providerRequestInterval: Duration
        get() = Duration.ZERO

    fun validate(request: ProviderSyncRequest): ProviderSyncPlan

    fun accountUnavailable(
        providerInstanceId: String?,
        statusHint: SyncAccount?,
    ): Throwable

    suspend fun refreshAccessToken(
        refreshToken: String,
        account: SyncAccount,
        now: Instant,
    ): RefreshedTokenSet

    suspend fun fetch(
        accessToken: String,
        account: SyncAccount,
        item: ProviderSyncItem,
        now: Instant,
    ): ProviderFetchedBatch

    fun sourcePayload(context: ProviderSourcePayloadContext): JsonObject =
        buildJsonObject {
            put("provider", providerCode)
            put("providerInstanceId", context.providerInstanceId)
            put("requestedFrom", context.item.from.toString())
            put("requestedTo", context.item.to.toString())
            put("dataType", context.item.dataType)
            passthroughPayloadKeys.forEach { key ->
                put(key, context.fetched.sourcePayload[key] ?: JsonArray(emptyList()))
            }
        }

    fun batchExternalId(
        providerInstanceId: String,
        item: ProviderSyncItem,
    ): String

    fun isUnauthorized(error: Throwable): Boolean

    fun isInvalidRefreshToken(error: Throwable): Boolean

    fun errorCode(error: Throwable): String

    fun errorAttributes(error: Throwable): Map<String, String> = emptyMap()
}
