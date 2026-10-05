package me.aquitano.external.google

import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import me.aquitano.health.application.providersync.PROVIDER_REQUEST_INTERVAL
import me.aquitano.health.application.providersync.ProviderFetchedBatch
import me.aquitano.health.application.providersync.ProviderSyncAdapter
import me.aquitano.health.application.providersync.RefreshedTokenSet
import me.aquitano.health.domain.ProviderSyncItem
import me.aquitano.health.shared.AppJson
import java.time.Duration
import java.time.Instant

class GoogleHealthSyncAdapter(
    private val client: GoogleHealthClient,
    private val normalizer: GoogleHealthNormalizer,
) : ProviderSyncAdapter {
    override val providerCode: String = GOOGLE_HEALTH_PROVIDER_CODE
    override val displayName: String = GOOGLE_HEALTH_DISPLAY_NAME
    override val dataTypes: List<String> = GOOGLE_HEALTH_DEFAULT_DATA_TYPES
    override val tokenRefreshFailureMessage: String = "Google OAuth token refresh failed"
    override val providerRequestInterval: Duration = PROVIDER_REQUEST_INTERVAL

    override suspend fun refreshAccessToken(
        refreshToken: String,
        now: Instant,
    ): RefreshedTokenSet = client.refreshToken(refreshToken, now)

    override suspend fun fetch(
        accessToken: String,
        item: ProviderSyncItem,
    ): ProviderFetchedBatch {
        val maxPageSize = if (item.dataType == "sleep") 25 else MAX_PAGE_SIZE
        val result =
            client.fetchDataPoints(
                accessToken,
                item.dataType,
                item.from,
                item.to,
                minOf(item.pageSize ?: MAX_PAGE_SIZE, maxPageSize),
            )
        return ProviderFetchedBatch(
            pages = AppJson.encodeToJsonElement(result.pages).jsonArray,
            sourceRecords = result.dataPoints,
            records = normalizer.normalize(result),
        )
    }

    override fun isUnauthorized(error: Throwable): Boolean = error is GoogleHealthUnauthorizedException

    override fun isInvalidRefreshToken(error: Throwable): Boolean =
        error is GoogleHealthUnauthorizedException ||
            (error is GoogleHealthHttpException && error.oauthError == "invalid_grant")

    override fun providerErrorCode(error: Throwable): String? = (error as? GoogleHealthHttpException)?.code

    private companion object {
        const val MAX_PAGE_SIZE = 10000
    }
}
