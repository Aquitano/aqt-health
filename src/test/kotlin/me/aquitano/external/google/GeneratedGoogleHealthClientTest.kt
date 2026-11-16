package me.aquitano.external.google

import com.google.api.gax.rpc.ApiException
import com.google.api.gax.rpc.StatusCode
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.devicesandservices.health.v4.DailyRollUpDataPointsRequest
import com.google.devicesandservices.health.v4.DailyRollUpDataPointsResponse
import com.google.devicesandservices.health.v4.DailyRollupDataPoint
import com.google.devicesandservices.health.v4.ReconcileDataPointsRequest
import com.google.devicesandservices.health.v4.ReconcileDataPointsResponse
import com.google.devicesandservices.health.v4.ReconciledDataPoint
import com.google.protobuf.util.JsonFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import me.aquitano.health.application.providersync.RefreshedTokenSet
import java.net.URI
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeneratedGoogleHealthClientTest {
    @Test
    fun dataPointsServiceSettingsUseProvidedOAuthAccessToken() {
        val settings = dataPointsServiceSettings("oauth-access-token")
        val metadata =
            settings.credentialsProvider
                .credentials
                .getRequestMetadata(URI("https://health.googleapis.com"))

        assertEquals(listOf("Bearer oauth-access-token"), metadata["Authorization"])
    }

    @Test
    fun fetchDataPointsFiltersEachSupportedDataTypeAndConvertsItToNormalizerShape() =
        runBlocking {
            val fixture = Fixture()
            val pointsByDataType =
                mapOf(
                    GoogleHealthDataType.Steps to (stepsPoint() to "steps.interval.start_time"),
                    GoogleHealthDataType.Sleep to (sleepPoint() to "sleep.interval.end_time"),
                    GoogleHealthDataType.HeartRate to (heartRatePoint() to "heart_rate.sample_time.physical_time"),
                    GoogleHealthDataType.Weight to (weightPoint() to "weight.sample_time.physical_time"),
                    GoogleHealthDataType.BodyFat to (bodyFatPoint() to "body_fat.sample_time.physical_time"),
                    GoogleHealthDataType.HeartRateVariability to (heartRateVariabilityPoint() to "heart_rate_variability.sample_time.physical_time"),
                    GoogleHealthDataType.RespiratoryRateSleepSummary to
                        (respiratoryRatePoint() to "respiratory_rate_sleep_summary.sample_time.physical_time"),
                )
            // Every reconciled type is listed above or is a daily type; activity uses roll-ups.
            assertEquals(
                GoogleHealthDataType.entries.filter { it.fetch is GoogleHealthFetch.Reconcile && !(it.fetch as GoogleHealthFetch.Reconcile).byDate }.toSet(),
                pointsByDataType.keys,
            )

            pointsByDataType.forEach { (dataType, pointAndFilterField) ->
                val (point, filterField) = pointAndFilterField
                fixture.service.responses +=
                    ReconcileDataPointsResponse
                        .newBuilder()
                        .addDataPoints(point)
                        .build()

                val result =
                    fixture.client.fetchDataPoints(
                        accessToken = "access-token",
                        dataType = dataType,
                        from = fixture.from,
                        to = fixture.to,
                        pageSize = 1000,
                    )

                val request = fixture.service.requests.last()
                assertEquals("users/me/dataTypes/${dataType.code}", request.parent, dataType.code)
                assertEquals("users/me/dataSourceFamilies/all-sources", request.dataSourceFamily, dataType.code)
                assertEquals(
                    """$filterField >= "${fixture.from}" AND $filterField < "${fixture.to}"""",
                    request.filter,
                    dataType.code,
                )
                val normalized = GoogleHealthNormalizer().normalize(result)
                assertEquals(1, normalized.size, dataType.code)
                assertEquals(
                    point.dataPointName,
                    result.dataPoints
                        .single()["dataPointName"]
                        ?.jsonPrimitive
                        ?.content,
                    dataType.code,
                )
            }
        }

    @Test
    fun dailyTypesFilterByTheWindowDatesIncludingTheStillOpenDay() =
        runBlocking {
            val fixture = Fixture()
            repeat(2) { fixture.service.responses += ReconcileDataPointsResponse.getDefaultInstance() }

            fixture.client.fetchDataPoints("access-token", GoogleHealthDataType.DailyRestingHeartRate, fixture.from, fixture.to, 1000)
            fixture.client.fetchDataPoints(
                "access-token",
                GoogleHealthDataType.DailyRestingHeartRate,
                fixture.to,
                fixture.to.plusSeconds(3600),
                1000,
            )

            assertEquals(
                listOf(
                    """daily_resting_heart_rate.date >= "2026-04-01" AND daily_resting_heart_rate.date < "2026-04-02"""",
                    """daily_resting_heart_rate.date >= "2026-04-02" AND daily_resting_heart_rate.date < "2026-04-03"""",
                ),
                fixture.service.requests.map { it.filter },
            )
        }

    @Test
    fun activityMergesTheDailyRollUpsIntoOnePointPerDate() =
        runBlocking {
            val fixture = Fixture()
            fixture.service.rollUps["distance"] = rollUp("""{"civilStartTime": {"date": {"year": 2026, "month": 4, "day": 1}}, "distance": {"millimetersSum": "5000000"}}""")
            fixture.service.rollUps["total-calories"] = rollUp("""{"civilStartTime": {"date": {"year": 2026, "month": 4, "day": 1}}, "totalCalories": {"kcalSum": 2100.5}}""")
            fixture.service.rollUps["active-minutes"] = DailyRollUpDataPointsResponse.getDefaultInstance()
            fixture.service.rollUps["heart-rate"] = rollUp("""{"civilStartTime": {"date": {"year": 2026, "month": 4, "day": 1}}, "heartRate": {"beatsPerMinuteAvg": 70.4}}""")

            val result = fixture.client.fetchDataPoints("access-token", GoogleHealthDataType.Activity, fixture.from, fixture.to, 1000)

            assertEquals(listOf("distance", "total-calories", "active-minutes", "heart-rate"), fixture.service.rollUpRequests.map { it.parent.substringAfterLast('/') })
            assertTrue(fixture.service.rollUpRequests.all { it.dataSourceFamily == "users/me/dataSourceFamilies/all-sources" && it.windowSizeDays == 1 })
            assertEquals(
                1,
                fixture.service.rollUpRequests
                    .first()
                    .range.start.date.day,
            )
            assertEquals(
                2,
                fixture.service.rollUpRequests
                    .first()
                    .range.end.date.day,
            )
            assertEquals(listOf(setOf("date", "distance", "totalCalories", "heartRate")), result.dataPoints.map { it.keys })
            assertEquals(4, result.pages.size)
        }

    @Test
    fun fetchDataPointsCollectsPaginatedResponsesAndRecordsPageIndexes() =
        runBlocking {
            val fixture = Fixture()
            fixture.service.responses +=
                ReconcileDataPointsResponse
                    .newBuilder()
                    .addDataPoints(stepsPoint("steps-1"))
                    .setNextPageToken("next-page")
                    .build()
            fixture.service.responses +=
                ReconcileDataPointsResponse
                    .newBuilder()
                    .addDataPoints(stepsPoint("steps-2"))
                    .build()

            val result = fixture.client.fetchDataPoints("access-token", GoogleHealthDataType.Steps, fixture.from, fixture.to, 1000)

            assertEquals(listOf(0, 1), result.pages.map { it.pageIndex })
            assertEquals(listOf("steps-1", "steps-2"), result.dataPoints.map { it["dataPointName"]?.jsonPrimitive?.content })
            assertEquals("next-page", fixture.service.requests[1].pageToken)
        }

    @Test
    fun fetchDataPointsRejectsRepeatedPageToken() =
        runBlocking {
            val fixture = Fixture()
            fixture.service.responses += ReconcileDataPointsResponse.newBuilder().setNextPageToken("same-token").build()
            fixture.service.responses += ReconcileDataPointsResponse.newBuilder().setNextPageToken("same-token").build()

            val error =
                assertFailsWith<GoogleHealthHttpException> {
                    fixture.client.fetchDataPoints("access-token", GoogleHealthDataType.Steps, fixture.from, fixture.to, 1000)
                }

            assertEquals("google_health_pagination_loop", error.code)
        }

    @Test
    fun fetchDataPointsEnforcesPageLimit() =
        runBlocking {
            val fixture = Fixture(maxPages = 1)
            fixture.service.responses += ReconcileDataPointsResponse.newBuilder().setNextPageToken("next").build()

            val error =
                assertFailsWith<GoogleHealthHttpException> {
                    fixture.client.fetchDataPoints("access-token", GoogleHealthDataType.Steps, fixture.from, fixture.to, 1000)
                }

            assertEquals("google_health_page_limit_exceeded", error.code)
        }

    @Test
    fun fetchDataPointsMapsUnauthenticatedToUnauthorizedException() =
        runBlocking {
            val fixture = Fixture()
            fixture.service.nextFailure = apiException(StatusCode.Code.UNAUTHENTICATED)

            assertFailsWith<GoogleHealthUnauthorizedException> {
                fixture.client.fetchDataPoints("access-token", GoogleHealthDataType.Steps, fixture.from, fixture.to, 1000)
            }
            Unit
        }

    @Test
    fun anExpiredTokenTheAuthLibraryCannotRefreshCountsAsUnauthorized() =
        runBlocking {
            val fixture = Fixture()
            val refreshFailure =
                assertFailsWith<IllegalStateException> {
                    GoogleCredentials.create(AccessToken.newBuilder().setTokenValue("expired").build()).refresh()
                }
            fixture.service.nextFailure = apiException(StatusCode.Code.UNKNOWN, cause = refreshFailure)

            assertFailsWith<GoogleHealthUnauthorizedException> {
                fixture.client.fetchDataPoints("access-token", GoogleHealthDataType.HeartRate, fixture.from, fixture.to, 1000)
            }
            Unit
        }

    @Test
    fun fetchDataPointsMapsResourceExhaustedToUpstreamFailure() =
        runBlocking {
            val fixture = Fixture()
            fixture.service.nextFailure = apiException(StatusCode.Code.RESOURCE_EXHAUSTED)

            val error =
                assertFailsWith<GoogleHealthHttpException> {
                    fixture.client.fetchDataPoints("access-token", GoogleHealthDataType.Steps, fixture.from, fixture.to, 1000)
                }

            assertEquals("google_health_upstream_failed", error.code)
        }

    @Test
    fun transportIsSharedAcrossFetchesAndRebuiltOnlyForANewToken() =
        runBlocking {
            val fixture = Fixture()
            repeat(3) {
                fixture.service.responses += ReconcileDataPointsResponse.newBuilder().build()
            }

            fixture.client.fetchDataPoints("token-1", GoogleHealthDataType.Steps, fixture.from, fixture.to, 1000)
            fixture.client.fetchDataPoints("token-1", GoogleHealthDataType.Sleep, fixture.from, fixture.to, 1000)
            assertEquals(listOf("token-1"), fixture.createdTokens)

            // A refreshed token replaces the transport, and the retired one is closed.
            fixture.client.fetchDataPoints("token-2", GoogleHealthDataType.Steps, fixture.from, fixture.to, 1000)
            assertEquals(listOf("token-1", "token-2"), fixture.createdTokens)
            assertEquals(1, fixture.service.closes)

            fixture.client.close()
            assertEquals(2, fixture.service.closes)
        }

    @Test
    fun replacedTransportStaysOpenUntilItsInflightFetchCompletes() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val oldCloses = AtomicInteger()
            val oldService =
                object : GoogleHealthDataPointsService {
                    override fun reconcileDataPoints(request: ReconcileDataPointsRequest): ReconcileDataPointsResponse {
                        started.complete(Unit)
                        check(release.await(10, TimeUnit.SECONDS))
                        return ReconcileDataPointsResponse.getDefaultInstance()
                    }

                    override fun dailyRollUpDataPoints(request: DailyRollUpDataPointsRequest): DailyRollUpDataPointsResponse = error("not used")

                    override fun close() {
                        oldCloses.incrementAndGet()
                    }
                }
            val newService =
                FakeDataPointsService().apply {
                    responses += ReconcileDataPointsResponse.getDefaultInstance()
                }
            val client =
                GeneratedGoogleHealthClient(
                    FakeOAuthClient(),
                    object : GoogleHealthDataPointsServiceFactory() {
                        override fun create(accessToken: String) = if (accessToken == "old") oldService else newService
                    },
                )
            val from = Instant.parse("2026-04-01T00:00:00Z")
            val to = from.plusSeconds(86400)
            val first = async { client.fetchDataPoints("old", GoogleHealthDataType.Steps, from, to, 1000) }
            try {
                withTimeout(10_000) { started.await() }
                client.fetchDataPoints("new", GoogleHealthDataType.Steps, from, to, 1000)
                client.close()
                assertEquals(0, oldCloses.get())
                assertEquals(1, newService.closes)
            } finally {
                release.countDown()
                client.close()
            }
            first.await()
            assertEquals(1, oldCloses.get())
            assertEquals(1, newService.closes)
        }

    @Test
    fun fetchAfterCloseIsRejectedInsteadOfBuildingAnUnclosableTransport() =
        runBlocking {
            val fixture = Fixture()
            fixture.client.close()

            val error =
                assertFailsWith<GoogleHealthHttpException> {
                    fixture.client.fetchDataPoints("token-1", GoogleHealthDataType.Steps, fixture.from, fixture.to, 1000)
                }

            assertEquals("google_health_client_closed", error.code)
            assertTrue(fixture.createdTokens.isEmpty())
        }

    private class Fixture(
        maxPages: Int = MAX_GOOGLE_HEALTH_PAGES,
    ) {
        val from: Instant = Instant.parse("2026-04-01T00:00:00Z")
        val to: Instant = Instant.parse("2026-04-02T00:00:00Z")
        val service = FakeDataPointsService()
        val createdTokens = mutableListOf<String>()
        val client =
            GeneratedGoogleHealthClient(
                oauthClient = FakeOAuthClient(),
                dataPointsServiceFactory =
                    object : GoogleHealthDataPointsServiceFactory() {
                        override fun create(accessToken: String): GoogleHealthDataPointsService {
                            createdTokens.add(accessToken)
                            return service
                        }
                    },
                maxPages = maxPages,
            )
    }

    private class FakeDataPointsService : GoogleHealthDataPointsService {
        val requests = mutableListOf<ReconcileDataPointsRequest>()
        val responses = ArrayDeque<ReconcileDataPointsResponse>()
        val rollUpRequests = mutableListOf<DailyRollUpDataPointsRequest>()
        val rollUps = mutableMapOf<String, DailyRollUpDataPointsResponse>()
        var nextFailure: ApiException? = null
        var closes = 0

        override fun dailyRollUpDataPoints(request: DailyRollUpDataPointsRequest): DailyRollUpDataPointsResponse {
            rollUpRequests.add(request)
            return rollUps.getValue(request.parent.substringAfterLast('/'))
        }

        override fun reconcileDataPoints(request: ReconcileDataPointsRequest): ReconcileDataPointsResponse {
            requests.add(request)
            nextFailure?.let {
                nextFailure = null
                throw it
            }
            return responses.removeFirst()
        }

        override fun close() {
            closes += 1
        }
    }

    private class FakeOAuthClient : GoogleHealthClient {
        override suspend fun exchangeCode(
            code: String,
            now: Instant,
        ): RefreshedTokenSet = error("not used")

        override suspend fun refreshToken(
            refreshToken: String,
            now: Instant,
        ): RefreshedTokenSet = error("not used")

        override suspend fun fetchDataPoints(
            accessToken: String,
            dataType: GoogleHealthDataType,
            from: Instant,
            to: Instant,
            pageSize: Int,
        ): GoogleHealthFetchResult = error("not used")
    }

    private fun apiException(
        code: StatusCode.Code,
        cause: Throwable? = null,
    ): ApiException =
        ApiException(
            "failure",
            cause,
            object : StatusCode {
                override fun getCode(): StatusCode.Code = code

                override fun getTransportCode(): Any = code
            },
            false,
        )

    private fun stepsPoint(name: String = "google-steps-1"): ReconciledDataPoint =
        dataPoint(
            """
        {
          "dataPointName": "$name",
          "steps": {
            "interval": {
              "startTime": "2026-04-01T08:00:00Z",
              "endTime": "2026-04-01T09:00:00Z"
            },
            "count": "1200"
          }
        }
        """,
        )

    private fun sleepPoint(): ReconciledDataPoint =
        dataPoint(
            """
        {
          "dataPointName": "google-sleep-1",
          "sleep": {
            "interval": {
              "startTime": "2026-03-31T22:00:00Z",
              "endTime": "2026-04-01T06:00:00Z"
            },
            "stages": [
              {
                "type": "LIGHT",
                "startTime": "2026-03-31T22:00:00Z",
                "endTime": "2026-04-01T01:00:00Z"
              }
            ]
          }
        }
        """,
        )

    private fun heartRatePoint(): ReconciledDataPoint =
        dataPoint(
            """
        {
          "dataPointName": "google-hr-1",
          "heartRate": {
            "sampleTime": {
              "physicalTime": "2026-04-01T08:30:00Z"
            },
            "beatsPerMinute": "62"
          }
        }
        """,
        )

    private fun weightPoint(): ReconciledDataPoint =
        dataPoint(
            """
        {
          "dataPointName": "google-weight-1",
          "weight": {
            "sampleTime": {
              "physicalTime": "2026-04-01T07:00:00Z"
            },
            "weightGrams": 82400.0
          }
        }
        """,
        )

    private fun bodyFatPoint(): ReconciledDataPoint =
        dataPoint(
            """
        {
          "dataPointName": "google-body-fat-1",
          "bodyFat": {
            "sampleTime": {
              "physicalTime": "2026-04-01T07:00:00Z"
            },
            "percentage": 18.2
          }
        }
        """,
        )

    private fun heartRateVariabilityPoint(): ReconciledDataPoint =
        dataPoint(
            """
        {
          "dataPointName": "google-hrv-1",
          "heartRateVariability": {
            "sampleTime": {
              "physicalTime": "2026-04-01T03:15:00Z"
            },
            "rootMeanSquareOfSuccessiveDifferencesMilliseconds": 42.7
          }
        }
        """,
        )

    private fun respiratoryRatePoint(): ReconciledDataPoint =
        dataPoint(
            """
        {
          "dataPointName": "google-respiratory-rate-1",
          "respiratoryRateSleepSummary": {
            "sampleTime": {
              "physicalTime": "2026-04-01T06:00:00Z"
            },
            "fullSleepStats": {
              "breathsPerMinute": 14.4
            }
          }
        }
        """,
        )

    private fun rollUp(json: String): DailyRollUpDataPointsResponse {
        val builder = DailyRollupDataPoint.newBuilder()
        JsonFormat.parser().merge(json, builder)
        return DailyRollUpDataPointsResponse.newBuilder().addRollupDataPoints(builder).build()
    }

    private fun dataPoint(json: String): ReconciledDataPoint {
        val builder = ReconciledDataPoint.newBuilder()
        JsonFormat.parser().merge(json.trimIndent(), builder)
        return builder.build()
    }
}
