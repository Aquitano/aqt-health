package me.aquitano.external.google

import kotlinx.serialization.json.JsonArray
import me.aquitano.health.application.providersync.PROVIDER_REQUEST_INTERVAL
import me.aquitano.health.application.providersync.ProviderFetchedBatch
import me.aquitano.health.application.providersync.ProviderSyncAdapter
import me.aquitano.health.application.providersync.RefreshedTokenSet
import me.aquitano.health.domain.ProviderSyncItem
import java.time.Duration
import java.time.Instant

class GoogleHealthSyncAdapter(
    private val client: GoogleHealthClient,
    private val normalizer: GoogleHealthNormalizer,
) : ProviderSyncAdapter {
    override val providerCode: String = GOOGLE_HEALTH_PROVIDER_CODE
    override val displayName: String = GOOGLE_HEALTH_DISPLAY_NAME
    override val dataTypes: List<String> = GoogleHealthDataType.codes
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
        val dataType = GoogleHealthDataType.fromCode(item.dataType)
        val result =
            client.fetchDataPoints(
                accessToken,
                dataType,
                item.from,
                item.to,
                item.pageSize?.coerceAtMost(dataType.maxPageSize) ?: dataType.maxPageSize,
            )
        return ProviderFetchedBatch(
            pages = JsonArray(result.pages.map { it.toJson() }),
            sourceRecords = result.dataPoints,
            records = normalizer.normalize(result),
        )
    }

    override fun isUnauthorized(error: Throwable): Boolean = error is GoogleHealthUnauthorizedException

    override fun isInvalidRefreshToken(error: Throwable): Boolean =
        error is GoogleHealthUnauthorizedException ||
            (error is GoogleHealthHttpException && error.oauthError == "invalid_grant")

    override fun providerErrorCode(error: Throwable): String? = (error as? GoogleHealthHttpException)?.code
}
