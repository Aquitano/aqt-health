package me.aquitano.external.google

import com.google.api.gax.core.FixedCredentialsProvider
import com.google.api.gax.rpc.ApiException
import com.google.api.gax.rpc.StatusCode
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.OAuth2Credentials
import com.google.devicesandservices.health.v4.*
import com.google.protobuf.MessageOrBuilder
import com.google.protobuf.util.JsonFormat
import com.google.type.Date
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.aquitano.health.infrastructure.logging.*
import me.aquitano.health.shared.AppJson
import java.time.Instant
import java.time.LocalDate

private val generatedClientLogger = KotlinLogging.logger {}

class GeneratedGoogleHealthClient(
    private val oauthClient: GoogleHealthOAuthClient,
    dataPointsServiceFactory: GoogleHealthDataPointsServiceFactory = GoogleHealthDataPointsServiceFactory(),
    private val maxPages: Int = MAX_GOOGLE_HEALTH_PAGES,
) : GoogleHealthClient,
    GoogleHealthOAuthClient by oauthClient,
    AutoCloseable {
    private val services = DataPointsServiceCache(dataPointsServiceFactory)

    override suspend fun fetchDataPoints(
        accessToken: String,
        dataType: GoogleHealthDataType,
        from: Instant,
        to: Instant,
        pageSize: Int,
    ): GoogleHealthFetchResult {
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
        dataType: GoogleHealthDataType,
        from: Instant,
        to: Instant,
        pageSize: Int,
    ): GoogleHealthFetchResult =
        when (val fetch = dataType.fetch) {
            is GoogleHealthFetch.Reconcile -> reconcile(service, dataType, fetch.filter(from, to), pageSize)
            GoogleHealthFetch.ActivityRollUp -> activityRollUp(service, dataType, from, to)
        }

    private fun reconcile(
        service: GoogleHealthDataPointsService,
        dataType: GoogleHealthDataType,
        filter: String,
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
                    "Google Health ${dataType.code} pagination exceeded $maxPages pages",
                )
            }

            val request =
                ReconcileDataPointsRequest
                    .newBuilder()
                    .setParent(dataTypeParent(dataType.code))
                    .setPageSize(pageSize)
                    .setFilter(filter)
                    .setDataSourceFamily(ALL_SOURCES)
                    .also { builder ->
                        if (pageToken.isNotBlank()) builder.setPageToken(pageToken)
                    }.build()

            val response = callUpstream(dataType) { service.reconcileDataPoints(request) }
            val pageDataPoints = response.dataPointsList.map(::toJson)
            val nextPageToken =
                response.nextPageToken.takeIf { it.isNotBlank() }

            pages.add(
                GoogleHealthPage(
                    pageIndex = pageIndex,
                    payload =
                        buildJsonObject {
                            put("dataPoints", JsonArray(pageDataPoints))
                            put("nextPageToken", nextPageToken ?: "")
                        },
                ),
            )
            dataPoints.addAll(pageDataPoints)
            pageIndex += 1

            if (pageIndex == 1 || pageIndex % 25 == 0) {
                generatedClientLogger.infoWithContext(
                    "google_health_generated_page_fetched",
                    "dataType" to dataType.code,
                    "pages" to pageIndex,
                    "dataPoints" to dataPoints.size,
                )
            }

            if (nextPageToken != null && !seenPageTokens.add(nextPageToken)) {
                throw GoogleHealthHttpException(
                    "google_health_pagination_loop",
                    "Google Health ${dataType.code} returned a repeated page token after $pageIndex pages",
                )
            }
            pageToken = nextPageToken.orEmpty()
        } while (pageToken.isNotBlank())

        return GoogleHealthFetchResult(dataType, pages, dataPoints)
    }

    private fun activityRollUp(
        service: GoogleHealthDataPointsService,
        dataType: GoogleHealthDataType,
        from: Instant,
        to: Instant,
    ): GoogleHealthFetchResult {
        val (fromDate, toDate) = civilDates(from, to)
        val range =
            CivilTimeInterval
                .newBuilder()
                .setStart(CivilDateTime.newBuilder().setDate(fromDate.toProtoDate()))
                .setEnd(CivilDateTime.newBuilder().setDate(toDate.toProtoDate()))
                .build()
        val byDate = sortedMapOf<String, MutableMap<String, JsonElement>>()
        val pages =
            GoogleHealthFetch.ActivityRollUp.rollUpTypes.mapIndexed { pageIndex, rollUpType ->
                val request =
                    DailyRollUpDataPointsRequest
                        .newBuilder()
                        .setParent(dataTypeParent(rollUpType))
                        .setRange(range)
                        .setWindowSizeDays(1)
                        .setDataSourceFamily(ALL_SOURCES)
                        .build()
                val points = callUpstream(dataType) { service.dailyRollUpDataPoints(request) }.rollupDataPointsList.map(::toJson)
                points.forEach { point ->
                    val date = point.civilStartDate() ?: return@forEach
                    point
                        .filterKeys { it != "civilStartTime" && it != "civilEndTime" }
                        .forEach { (field, value) -> byDate.getOrPut(date) { mutableMapOf("date" to JsonPrimitive(date)) }[field] = value }
                }
                GoogleHealthPage(
                    pageIndex = pageIndex,
                    payload =
                        buildJsonObject {
                            put("rollUpType", rollUpType)
                            put("rollupDataPoints", JsonArray(points))
                        },
                )
            }
        return GoogleHealthFetchResult(dataType, pages, byDate.values.map(::JsonObject))
    }

    private fun <T> callUpstream(
        dataType: GoogleHealthDataType,
        call: () -> T,
    ): T =
        try {
            call()
        } catch (exception: ApiException) {
            throw mapApiException(exception, dataType)
        }

    private fun mapApiException(
        exception: ApiException,
        dataType: GoogleHealthDataType,
    ): RuntimeException =
        if (exception.statusCode.code == StatusCode.Code.UNAUTHENTICATED || exception.isRejectedFixedAccessToken()) {
            GoogleHealthUnauthorizedException("Google Health access token is unauthorized")
        } else {
            GoogleHealthHttpException(
                "google_health_upstream_failed",
                "Google Health ${dataType.code} request failed with ${exception.statusCode.code}",
                cause = exception,
            )
        }

    /**
     * On a 401 the auth library tries to refresh the fixed access token it was given, cannot, and
     * gax reports that as UNKNOWN. It means the token expired, so the pipeline must refresh it.
     */
    private fun ApiException.isRejectedFixedAccessToken(): Boolean =
        generateSequence<Throwable>(this) { it.cause }.any { cause ->
            cause is IllegalStateException && cause.stackTrace.firstOrNull()?.className == OAuth2Credentials::class.java.name
        }

    private fun toJson(message: MessageOrBuilder): JsonObject = AppJson.parseToJsonElement(PROTO_JSON_PRINTER.print(message)).jsonObject

    private fun JsonObject.civilStartDate(): String? {
        val date = this["civilStartTime"]?.jsonObject?.get("date")?.jsonObject ?: return null
        val part = { key: String -> date[key]?.jsonPrimitive?.intOrNull }
        return LocalDate.of(part("year") ?: return null, part("month") ?: return null, part("day") ?: return null).toString()
    }

    companion object {
        private val PROTO_JSON_PRINTER: JsonFormat.Printer =
            JsonFormat
                .printer()
                .omittingInsignificantWhitespace()

        /** Narrower source families drop real data, such as scale weights written through Health Connect. */
        private const val ALL_SOURCES = "users/me/dataSourceFamilies/all-sources"

        private fun dataTypeParent(code: String) = DataTypeName.of("me", code).toString()

        private fun LocalDate.toProtoDate(): Date =
            Date
                .newBuilder()
                .setYear(year)
                .setMonth(monthValue)
                .setDay(dayOfMonth)
                .build()
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
    private class Entry(
        val service: GoogleHealthDataPointsService,
    ) {
        var users: Int = 0
        var retired: Boolean = false
    }

    private val lock = Any()
    private var currentToken: String? = null
    private var current: Entry? = null
    private var closed = false

    fun <T> use(
        accessToken: String,
        block: (GoogleHealthDataPointsService) -> T,
    ): T {
        val entry = acquire(accessToken)
        try {
            return block(entry.service)
        } finally {
            release(entry)
        }
    }

    private fun acquire(accessToken: String): Entry =
        synchronized(lock) {
            // Shutdown closes the cache before the sync producers have necessarily stopped. Without
            // this, a fetch that starts after close() would build a replacement transport that nothing
            // ever closes.
            if (closed) {
                throw GoogleHealthHttpException(
                    "google_health_client_closed",
                    "Google Health client is shutting down",
                )
            }
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

    private fun release(entry: Entry) =
        synchronized(lock) {
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

    override fun close() =
        synchronized(lock) {
            closed = true
            retireCurrent()
        }
}

open class GoogleHealthDataPointsServiceFactory(
    private val apiBaseUrl: String? = null,
) {
    open fun create(accessToken: String): GoogleHealthDataPointsService = GeneratedGoogleHealthDataPointsService(accessToken, apiBaseUrl)
}

interface GoogleHealthDataPointsService : AutoCloseable {
    fun reconcileDataPoints(request: ReconcileDataPointsRequest): ReconcileDataPointsResponse

    fun dailyRollUpDataPoints(request: DailyRollUpDataPointsRequest): DailyRollUpDataPointsResponse
}

private class GeneratedGoogleHealthDataPointsService(
    accessToken: String,
    apiBaseUrl: String?,
) : GoogleHealthDataPointsService {
    private val client =
        DataPointsServiceClient.create(
            dataPointsServiceSettings(
                accessToken,
                apiBaseUrl,
            ),
        )

    override fun reconcileDataPoints(request: ReconcileDataPointsRequest): ReconcileDataPointsResponse = client.reconcileDataPointsCallable().call(request)

    override fun dailyRollUpDataPoints(request: DailyRollUpDataPointsRequest): DailyRollUpDataPointsResponse = client.dailyRollUpDataPointsCallable().call(request)

    override fun close() {
        client.close()
    }
}

internal fun dataPointsServiceSettings(
    accessToken: String,
    apiBaseUrl: String? = null,
): DataPointsServiceSettings {
    val builder =
        DataPointsServiceSettings
            .newHttpJsonBuilder()
            .setCredentialsProvider(
                FixedCredentialsProvider.create(
                    GoogleCredentials.create(AccessToken.newBuilder().setTokenValue(accessToken).build()),
                ),
            )
    apiBaseUrl?.toEndpoint()?.let(builder::setEndpoint)
    return builder.build()
}

private fun String.toEndpoint(): String =
    trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')
