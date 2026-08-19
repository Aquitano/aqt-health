package me.aquitano.external.google

import com.google.api.gax.core.FixedCredentialsProvider
import com.google.api.gax.rpc.ApiException
import com.google.api.gax.rpc.StatusCode
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.devicesandservices.health.v4.*
import com.google.protobuf.util.JsonFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.aquitano.health.shared.AppJson
import io.github.oshai.kotlinlogging.KotlinLogging
import me.aquitano.health.infrastructure.logging.*
import java.time.Instant

private val generatedClientLogger = KotlinLogging.logger {}

class GeneratedGoogleHealthClient(
    private val oauthClient: GoogleHealthOAuthClient,
    dataPointsServiceFactory: GoogleHealthDataPointsServiceFactory = GoogleHealthDataPointsServiceFactory(),
    private val maxPages: Int = MAX_GOOGLE_HEALTH_PAGES,
) : GoogleHealthClient, GoogleHealthOAuthClient by oauthClient, AutoCloseable {
    private val services = DataPointsServiceCache(dataPointsServiceFactory)

    override suspend fun fetchDataPoints(
        accessToken: String,
        dataType: String,
        from: Instant,
        to: Instant,
        pageSize: Int,
    ): GoogleHealthFetchResult {
        validateSupportedDataType(dataType)
        // gax calls block the calling thread, so they stay off the Ktor worker threads.
        return withContext(Dispatchers.IO) {
            services.use(accessToken) { service ->
                fetchDataPoints(service, dataType, from, to, pageSize)
            }
        }
    }

    /** Releases the shared transport; the client is unusable afterwards. */
    override fun close() {
        services.close()
    }

    private fun fetchDataPoints(
        service: GoogleHealthDataPointsService,
        dataType: String,
        from: Instant,
        to: Instant,
        pageSize: Int,
    ): GoogleHealthFetchResult {
        val pages = mutableListOf<GoogleHealthPage>()
        val dataPoints = mutableListOf<JsonObject>()
        val seenPageTokens = mutableSetOf<String>()
        var pageToken = ""
        var pageIndex = 0

        do {
            if (pageIndex >= maxPages) {
                throw GoogleHealthHttpException(
                    "google_health_page_limit_exceeded",
                    "Google Health $dataType pagination exceeded $maxPages pages",
                )
            }

            val request = ListDataPointsRequest.newBuilder()
                .setParent(DataTypeName.of("me", dataType).toString())
                .setPageSize(pageSize)
                .setFilter(filterFor(dataType, from, to))
                .also { builder ->
                    if (pageToken.isNotBlank()) builder.setPageToken(pageToken)
                }
                .build()

            val response = callListDataPoints(service, request, dataType)
            val pageDataPoints = response.dataPointsList.map(::dataPointJson)
            val nextPageToken =
                response.nextPageToken.takeIf { it.isNotBlank() }

            pages.add(
                GoogleHealthPage(
                    pageIndex = pageIndex,
                    payload = buildJsonObject {
                        put("dataPoints", JsonArray(pageDataPoints))
                        put("nextPageToken", nextPageToken ?: "")
                    },
                )
            )
            dataPoints.addAll(pageDataPoints)
            pageIndex += 1

            if (pageIndex == 1 || pageIndex % 25 == 0) {
                generatedClientLogger.infoWithContext(
                    "google_health_generated_page_fetched",
                    "dataType" to dataType,
                    "pages" to pageIndex,
                    "dataPoints" to dataPoints.size,
                )
            }

            if (nextPageToken != null && !seenPageTokens.add(nextPageToken)) {
                throw GoogleHealthHttpException(
                    "google_health_pagination_loop",
                    "Google Health $dataType returned a repeated page token after $pageIndex pages",
                )
            }
            pageToken = nextPageToken.orEmpty()
        } while (pageToken.isNotBlank())

        return GoogleHealthFetchResult(dataType, pages, dataPoints)
    }

    private fun callListDataPoints(
        service: GoogleHealthDataPointsService,
        request: ListDataPointsRequest,
        dataType: String,
    ): ListDataPointsResponse =
        try {
            service.listDataPoints(request)
        } catch (exception: ApiException) {
            throw mapApiException(exception, dataType)
        }

    private fun mapApiException(
        exception: ApiException,
        dataType: String
    ): RuntimeException =
        when (exception.statusCode.code) {
            StatusCode.Code.UNAUTHENTICATED -> GoogleHealthUnauthorizedException(
                "Google Health access token is unauthorized"
            )

            else -> GoogleHealthHttpException(
                "google_health_upstream_failed",
                "Google Health $dataType request failed with ${exception.statusCode.code}"
            )
        }

    private fun validateSupportedDataType(dataType: String) {
        if (dataType !in GOOGLE_HEALTH_DEFAULT_DATA_TYPES) {
            throw GoogleHealthHttpException(
                "google_health_unsupported_data_type",
                "Unsupported Google Health data type: $dataType"
            )
        }
    }

    private fun filterFor(
        dataType: String,
        from: Instant,
        to: Instant
    ): String =
        when (dataType) {
            "steps" -> """steps.interval.start_time >= "$from" AND steps.interval.start_time < "$to""""
            "sleep" -> """sleep.interval.end_time >= "$from" AND sleep.interval.end_time < "$to""""
            "heart-rate" -> """heart_rate.sample_time.physical_time >= "$from" AND heart_rate.sample_time.physical_time < "$to""""
            "weight" -> """weight.sample_time.physical_time >= "$from" AND weight.sample_time.physical_time < "$to""""
            "body-fat" -> """body_fat.sample_time.physical_time >= "$from" AND body_fat.sample_time.physical_time < "$to""""
            else -> throw GoogleHealthHttpException(
                "google_health_unsupported_data_type",
                "Unsupported Google Health data type: $dataType"
            )
        }

    private fun dataPointJson(dataPoint: DataPoint): JsonObject =
        AppJson.parseToJsonElement(PROTO_JSON_PRINTER.print(dataPoint)).jsonObject

    companion object {
        private val PROTO_JSON_PRINTER: JsonFormat.Printer =
            JsonFormat.printer()
                .omittingInsignificantWhitespace()
    }
}

/**
 * Keeps one transport client alive across fetches instead of building and tearing one down per
 * call: a month-long, five-data-type sync is 155 fetches but needs a single connection pool.
 *
 * The access token is baked into the transport's credentials, so a refreshed token replaces the
 * cached service. Replaced services are reference counted and closed once their last in-flight
 * fetch returns, because a concurrent sync may still be paginating on one.
 */
private class DataPointsServiceCache(
    private val factory: GoogleHealthDataPointsServiceFactory,
) : AutoCloseable {
    private class Entry(val service: GoogleHealthDataPointsService) {
        var users: Int = 0
        var retired: Boolean = false
    }

    private val lock = Any()
    private var currentToken: String? = null
    private var current: Entry? = null

    fun <T> use(accessToken: String, block: (GoogleHealthDataPointsService) -> T): T {
        val entry = acquire(accessToken)
        try {
            return block(entry.service)
        } finally {
            release(entry)
        }
    }

    private fun acquire(accessToken: String): Entry = synchronized(lock) {
        current
            ?.takeIf { currentToken == accessToken && !it.retired }
            ?.let {
                it.users += 1
                return it
            }
        retireCurrent()
        val entry = Entry(factory.create(accessToken))
        entry.users = 1
        current = entry
        currentToken = accessToken
        entry
    }

    private fun release(entry: Entry) = synchronized(lock) {
        entry.users -= 1
        if (entry.retired && entry.users == 0) entry.service.close()
    }

    private fun retireCurrent() {
        val entry = current ?: return
        entry.retired = true
        if (entry.users == 0) entry.service.close()
        current = null
        currentToken = null
    }

    override fun close() = synchronized(lock) { retireCurrent() }
}

open class GoogleHealthDataPointsServiceFactory(
    private val apiBaseUrl: String? = null,
) {
    open fun create(accessToken: String): GoogleHealthDataPointsService =
        GeneratedGoogleHealthDataPointsService(accessToken, apiBaseUrl)
}

interface GoogleHealthDataPointsService : AutoCloseable {
    fun listDataPoints(request: ListDataPointsRequest): ListDataPointsResponse
}

private class GeneratedGoogleHealthDataPointsService(
    accessToken: String,
    apiBaseUrl: String?,
) : GoogleHealthDataPointsService {
    private val client = DataPointsServiceClient.create(
        dataPointsServiceSettings(
            accessToken,
            apiBaseUrl
        )
    )

    override fun listDataPoints(request: ListDataPointsRequest): ListDataPointsResponse =
        client.listDataPointsCallable().call(request)

    override fun close() {
        client.close()
    }
}

internal fun dataPointsServiceSettings(
    accessToken: String,
    apiBaseUrl: String? = null
): DataPointsServiceSettings {
    val builder = DataPointsServiceSettings.newHttpJsonBuilder()
        .setCredentialsProvider(
            FixedCredentialsProvider.create(
                GoogleCredentials.create(AccessToken.newBuilder().setTokenValue(accessToken).build())
            )
        )
    apiBaseUrl?.toEndpoint()?.let(builder::setEndpoint)
    return builder.build()
}

private fun String.toEndpoint(): String =
    trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')
