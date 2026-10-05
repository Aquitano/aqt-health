package me.aquitano.external.withings

import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import me.aquitano.health.application.providersync.PROVIDER_REQUEST_INTERVAL
import me.aquitano.health.application.providersync.ProviderFetchedBatch
import me.aquitano.health.application.providersync.ProviderSyncAdapter
import me.aquitano.health.application.providersync.RefreshedTokenSet
import me.aquitano.health.application.providersync.SyncWindow
import me.aquitano.health.domain.ProviderSyncItem
import me.aquitano.health.shared.AppJson
import java.time.Duration
import java.time.Instant

class WithingsSyncAdapter(
    private val client: WithingsClient,
    private val normalizer: WithingsNormalizer,
) : ProviderSyncAdapter {
    override val providerCode: String = WITHINGS_PROVIDER_CODE
    override val displayName: String = WITHINGS_DISPLAY_NAME
    override val dataTypes: List<String> = WITHINGS_DEFAULT_DATA_TYPES
    override val recordEmptyDataTypes: Boolean = true
    override val providerRequestInterval: Duration = PROVIDER_REQUEST_INTERVAL

    override suspend fun refreshAccessToken(
        refreshToken: String,
        now: Instant,
    ): RefreshedTokenSet = client.refreshToken(refreshToken, now).toRefreshedTokenSet()

    override suspend fun fetch(
        accessToken: String,
        item: ProviderSyncItem,
    ): ProviderFetchedBatch {
        val result = fetchDataType(accessToken, item.dataType, item.from, item.to)
        return ProviderFetchedBatch(
            pages = AppJson.encodeToJsonElement(result.pages).jsonArray,
            sourceRecords = result.records,
            records = normalizer.normalize(result, SyncWindow(item.from, item.to)),
        )
    }

    override fun isUnauthorized(error: Throwable): Boolean =
        error is WithingsHttpException &&
            error.code == "withings_data_request_failed" &&
            (error.providerStatus == 401 || error.httpStatus == 401)

    override fun isInvalidRefreshToken(error: Throwable): Boolean =
        error is WithingsHttpException &&
            error.code == "withings_token_request_failed" &&
            error.providerStatus == 401

    override fun providerErrorCode(error: Throwable): String? = (error as? WithingsHttpException)?.code

    override fun errorAttributes(error: Throwable): Map<String, String> =
        when (error) {
            is WithingsHttpException -> {
                buildMap {
                    error.httpStatus?.let { put("httpStatus", it.toString()) }
                    error.providerStatus?.let { put("providerStatus", it.toString()) }
                    error.providerAction?.let { put("providerAction", it) }
                    error.providerEndpoint?.let { put("providerEndpoint", it) }
                }
            }

            else -> {
                emptyMap()
            }
        }

    private suspend fun fetchDataType(
        accessToken: String,
        dataType: String,
        from: Instant,
        to: Instant,
    ): WithingsFetchResult =
        when (dataType) {
            "activity" -> {
                client.fetchActivity(
                    accessToken,
                    from,
                    to,
                    WITHINGS_ACTIVITY_FIELDS,
                )
            }

            "measures" -> {
                client.fetchMeasures(
                    accessToken,
                    from,
                    to,
                    WITHINGS_MEASURE_TYPES,
                    1,
                )
            }

            "sleep-summary" -> {
                client.fetchSleepSummary(
                    accessToken,
                    from,
                    to,
                    WITHINGS_SLEEP_SUMMARY_FIELDS,
                )
            }

            // Reaches back past the window start so a night that began on the previous UTC day is
            // fetched whole; the normalizer keeps the sessions that end inside the window.
            "sleep" -> {
                client.fetchSleep(
                    accessToken,
                    from.minus(WITHINGS_SLEEP_LOOKBEHIND),
                    to,
                    WITHINGS_SLEEP_FIELDS,
                )
            }

            else -> {
                throw WithingsHttpException(
                    "withings_unsupported_data_type",
                    "Unsupported Withings data type: $dataType",
                )
            }
        }
}
