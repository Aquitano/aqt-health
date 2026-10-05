package me.aquitano.external.google

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.aquitano.health.application.HealthProviderRegistry
import me.aquitano.health.application.ProviderStatusService
import me.aquitano.health.application.ProviderWorkflowService
import me.aquitano.health.application.providersync.RefreshedTokenSet
import me.aquitano.health.domain.ConflictException
import me.aquitano.health.domain.ProviderSyncRequest
import me.aquitano.health.domain.ServerConfigurationException
import me.aquitano.health.domain.StructuralMetricKinds
import me.aquitano.health.domain.UpstreamProviderException
import me.aquitano.health.infrastructure.config.DatabaseConfig
import me.aquitano.health.infrastructure.config.ProviderOAuthConfig
import me.aquitano.health.infrastructure.repositories.ProviderOAuthRepository
import me.aquitano.health.infrastructure.repositories.ScheduledSyncRepository
import me.aquitano.health.infrastructure.security.TokenCipher
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.PostgresTestDatabase
import me.aquitano.health.test.TEST_TOKEN_ENCRYPTION_KEY
import me.aquitano.health.test.countRows
import me.aquitano.health.test.ingestionService
import me.aquitano.health.test.queryInt
import me.aquitano.health.test.queryString
import me.aquitano.health.test.realDerivedRebuildExecutor
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class GoogleHealthProviderTest : PostgresIntegrationTest() {
    @Test
    fun oauthCallbackStoresEncryptedTokens() =
        runBlocking {
            val fixture = Fixture()
            val now = Instant.parse("2026-04-20T10:00:00Z")
            val start = fixture.providerWorkflowService.startOAuth("google-health", now)

            val state = Regex("state=([^&]+)").find(start.authorizationUrl)!!.groupValues[1]
            val response =
                fixture.providerWorkflowService.completeOAuth(
                    providerCode = "google-health",
                    code = "auth-code",
                    state = state,
                    error = null,
                    now = now.plusSeconds(1),
                )

            assertEquals("google-health-me", response.providerInstanceId)
            val accessCiphertext = fixture.databaseConfig.queryString("SELECT access_token_ciphertext FROM provider_oauth_accounts")!!
            val refreshCiphertext = fixture.databaseConfig.queryString("SELECT refresh_token_ciphertext FROM provider_oauth_accounts")!!
            assertFalse(accessCiphertext.contains("access-from-code"))
            assertFalse(refreshCiphertext.contains("refresh-from-code"))
            val cipher = TokenCipher(fixture.config.tokenEncryptionKey, GOOGLE_HEALTH_PROVIDER_CODE)
            assertEquals("access-from-code", cipher.decrypt(accessCiphertext))
            assertEquals("refresh-from-code", cipher.decrypt(refreshCiphertext))
        }

    @Test
    fun oauthCallbackPreservesConnectedAtForAlreadyConnectedAccount() =
        runBlocking {
            val fixture = Fixture()
            val firstConnectedAt = Instant.parse("2026-04-20T10:00:00Z")
            val firstStart = fixture.providerWorkflowService.startOAuth("google-health", firstConnectedAt)
            val firstState = Regex("state=([^&]+)").find(firstStart.authorizationUrl)!!.groupValues[1]
            fixture.providerWorkflowService.completeOAuth(
                providerCode = "google-health",
                code = "auth-code",
                state = firstState,
                error = null,
                now = firstConnectedAt,
            )

            val secondStart =
                fixture.providerWorkflowService.startOAuth(
                    "google-health",
                    firstConnectedAt.plusSeconds(60),
                )
            val secondState = Regex("state=([^&]+)").find(secondStart.authorizationUrl)!!.groupValues[1]
            fixture.providerWorkflowService.completeOAuth(
                providerCode = "google-health",
                code = "auth-code",
                state = secondState,
                error = null,
                now = firstConnectedAt.plusSeconds(120),
            )

            assertEquals(
                1,
                fixture.databaseConfig.queryInt(
                    "SELECT COUNT(*) FROM provider_oauth_accounts WHERE connected_at = TIMESTAMPTZ '2026-04-20T10:00:00Z'",
                ),
            )
        }

    @Test
    fun oauthCallbackMapsTokenExchangeFailureToUpstreamProviderError() =
        runBlocking {
            val fixture = Fixture()
            val now = Instant.parse("2026-04-20T10:00:00Z")
            val start = fixture.providerWorkflowService.startOAuth("google-health", now)
            val state = Regex("state=([^&]+)").find(start.authorizationUrl)!!.groupValues[1]
            fixture.client.nextExchangeFailure =
                GoogleHealthHttpException(
                    "google_health_token_exchange_failed",
                    "Google OAuth token request failed with 400",
                )

            val error =
                assertFailsWith<UpstreamProviderException> {
                    fixture.providerWorkflowService.completeOAuth(
                        providerCode = "google-health",
                        code = "auth-code",
                        state = state,
                        error = null,
                        now = now.plusSeconds(1),
                    )
                }

            assertEquals("google_health_token_exchange_failed", error.code)
            assertEquals(502, error.statusCode)
        }

    @Test
    fun oauthStartDoesNotStoreStateWhenProviderIsMisconfigured() =
        runBlocking {
            val fixture = Fixture(clientSecret = "")

            val error =
                assertFailsWith<ServerConfigurationException> {
                    fixture.providerWorkflowService.startOAuth("google-health", fixture.now)
                }

            assertEquals("google_health_not_configured", error.code)
            assertEquals(0, fixture.databaseConfig.countRows("provider_oauth_states"))
        }

    @Test
    fun refreshFailureMarksAccountNeedsReauth() =
        runBlocking {
            val fixture = Fixture()
            fixture.storeAccount(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                expiresAt = fixture.now.minusSeconds(1),
            )
            fixture.client.nextRefreshFailure = GoogleHealthUnauthorizedException("invalid refresh token")

            val error =
                assertFailsWith<ConflictException> {
                    fixture.provider.sync(
                        ProviderSyncRequest(
                            from = Instant.parse("2026-04-01T00:00:00Z"),
                            to = Instant.parse("2026-04-02T00:00:00Z"),
                            dataTypes = listOf("steps"),
                        ),
                        fixture.now,
                    )
                }

            assertEquals("google_health_needs_reauth", error.code)
            assertEquals(
                "needs_reauth",
                fixture.databaseConfig.queryString("SELECT account_status FROM provider_oauth_accounts"),
            )
            assertEquals(
                "google_health_needs_reauth",
                fixture.databaseConfig.queryString("SELECT last_auth_error_code FROM provider_oauth_accounts"),
            )
        }

    @Test
    fun needsReauthAccountIsNotSelectedForSync() =
        runBlocking {
            val fixture = Fixture()
            fixture.storeAccount(accessToken = "access-token", refreshToken = "refresh-token")
            fixture.providerRepository.markNeedsReauth(
                accountId = fixture.databaseConfig.queryInt("SELECT id FROM provider_oauth_accounts"),
                expectedRefreshTokenCiphertext = fixture.databaseConfig.queryString("SELECT refresh_token_ciphertext FROM provider_oauth_accounts")!!,
                errorCode = "google_health_needs_reauth",
                errorMessage = "invalid refresh token",
                now = fixture.now,
            )

            val error =
                assertFailsWith<ConflictException> {
                    fixture.provider.sync(
                        ProviderSyncRequest(
                            from = Instant.parse("2026-04-01T00:00:00Z"),
                            to = Instant.parse("2026-04-02T00:00:00Z"),
                            dataTypes = listOf("steps"),
                        ),
                        fixture.now,
                    )
                }

            assertEquals("google_health_needs_reauth", error.code)
            assertEquals(0, fixture.client.fetchRequests.size)
        }

    @Test
    fun syncIngestsExistingMetricTypesAndIsIdempotent() =
        runBlocking {
            val fixture = Fixture()
            fixture.storeAccount(accessToken = "access-token", refreshToken = "refresh-token")
            fixture.client.fetchResults += allMetricFetchResults()

            val request =
                ProviderSyncRequest(
                    from = Instant.parse("2026-04-01T00:00:00Z"),
                    to = Instant.parse("2026-04-02T00:00:00Z"),
                )
            val first = fixture.provider.sync(request, fixture.now)
            fixture.client.fetchResults += allMetricFetchResults()
            val second = fixture.provider.sync(request, fixture.now.plusSeconds(60))

            assertEquals(7, first.batches.size)
            assertEquals(7, second.batches.size)
            assertTrue(second.batches.all { it.duplicateBatch })
            assertEquals(7, fixture.client.fetchRequests.size)
            assertEquals(1, fixture.databaseConfig.countRows("step_samples"))
            assertEquals(1, fixture.databaseConfig.countRows("sleep_sessions"))
            assertEquals(2, fixture.databaseConfig.countRows("sleep_stages"))
            assertEquals(1, fixture.databaseConfig.countRows("sleep_summaries"))
            assertEquals(
                1,
                fixture.databaseConfig.queryInt("SELECT COUNT(*) FROM scalar_samples WHERE metric_type = 'hrv_rmssd'"),
            )
            assertEquals(
                1,
                fixture.databaseConfig.queryInt("SELECT COUNT(*) FROM scalar_samples WHERE metric_type = 'respiratory_rate'"),
            )
            assertEquals(
                1,
                fixture.databaseConfig.queryInt("SELECT COUNT(*) FROM scalar_samples WHERE metric_type = 'heart_rate'"),
            )
            assertEquals(
                2,
                fixture.databaseConfig.queryInt(
                    "SELECT COUNT(*) FROM scalar_samples WHERE metric_type IN " +
                        "('weight', 'body_fat', 'muscle', 'water', 'visceral_fat')",
                ),
            )
        }

    @Test
    fun syncRejectsRequestedGoogleProviderInstanceWithoutStoredAccount() =
        runBlocking {
            val fixture = Fixture()
            fixture.storeAccount(
                providerInstanceId = "google-user-1",
                accessToken = "access-token",
                refreshToken = "refresh-token",
            )

            val error =
                assertFailsWith<ConflictException> {
                    fixture.provider.sync(
                        ProviderSyncRequest(
                            providerInstanceId = "google-user-2",
                            from = Instant.parse("2026-04-01T00:00:00Z"),
                            to = Instant.parse("2026-04-02T00:00:00Z"),
                            dataTypes = listOf("steps"),
                        ),
                        fixture.now,
                    )
                }

            assertEquals("google_health_account_not_found", error.code)
            assertEquals(0, fixture.client.fetchRequests.size)
            assertEquals(0, fixture.databaseConfig.countRows("provider_sync_runs"))
        }

    @Test
    fun syncOverlappingRangesWithSameGoogleStepRecordDoNotCreateDuplicateRows() =
        runBlocking {
            val fixture = Fixture()
            fixture.storeAccount(accessToken = "access-token", refreshToken = "refresh-token")
            fixture.client.fetchResults += listOf(stepsFetchResult())
            fixture.client.fetchResults += listOf(stepsFetchResult())

            val first =
                fixture.provider.sync(
                    ProviderSyncRequest(
                        from = Instant.parse("2026-04-01T00:00:00Z"),
                        to = Instant.parse("2026-04-01T12:00:00Z"),
                        dataTypes = listOf("steps"),
                    ),
                    fixture.now,
                )
            val second =
                fixture.provider.sync(
                    ProviderSyncRequest(
                        from = Instant.parse("2026-04-01T06:00:00Z"),
                        to = Instant.parse("2026-04-02T00:00:00Z"),
                        dataTypes = listOf("steps"),
                    ),
                    fixture.now.plusSeconds(60),
                )

            assertEquals(1, first.batches.single().metricsCreated[StructuralMetricKinds.STEP_SAMPLES])
            assertEquals(0, second.batches.single().metricsCreated[StructuralMetricKinds.STEP_SAMPLES])
            assertEquals(1, second.batches.single().duplicateMetricsSkipped)
            assertEquals(2, fixture.client.fetchRequests.size)
            assertEquals(1, fixture.databaseConfig.countRows("step_samples"))
            assertEquals(1200, fixture.databaseConfig.queryInt("SELECT SUM(value)::int FROM canonical_step_day_bucket_contributions"))
        }

    @Test
    fun syncSkipsOverlappingGoogleStepIntervalsWithDifferentProviderIds() =
        runBlocking {
            val fixture = Fixture()
            fixture.storeAccount(accessToken = "access-token", refreshToken = "refresh-token")
            fixture.client.fetchResults +=
                listOf(
                    fetchResult(
                        "steps",
                        stepsPoint(
                            name = "google-steps-minute",
                            startAt = "2026-04-01T08:00:00Z",
                            endAt = "2026-04-01T08:01:00Z",
                            count = 20,
                        ),
                    ),
                )
            fixture.client.fetchResults +=
                listOf(
                    fetchResult(
                        "steps",
                        stepsPoint(
                            name = "google-steps-short",
                            startAt = "2026-04-01T08:00:30Z",
                            endAt = "2026-04-01T08:01:30Z",
                            count = 15,
                        ),
                    ),
                )

            val first =
                fixture.provider.sync(
                    ProviderSyncRequest(
                        from = Instant.parse("2026-04-01T00:00:00Z"),
                        to = Instant.parse("2026-04-01T12:00:00Z"),
                        dataTypes = listOf("steps"),
                    ),
                    fixture.now,
                )
            val second =
                fixture.provider.sync(
                    ProviderSyncRequest(
                        from = Instant.parse("2026-04-01T08:00:30Z"),
                        to = Instant.parse("2026-04-02T00:00:00Z"),
                        dataTypes = listOf("steps"),
                    ),
                    fixture.now.plusSeconds(60),
                )

            assertEquals(1, first.batches.single().metricsCreated[StructuralMetricKinds.STEP_SAMPLES])
            assertEquals(0, second.batches.single().metricsCreated[StructuralMetricKinds.STEP_SAMPLES])
            assertEquals(1, second.batches.single().duplicateMetricsSkipped)
            assertEquals(1, fixture.databaseConfig.countRows("step_samples"))
            assertEquals(20, fixture.databaseConfig.queryInt("SELECT SUM(value)::int FROM canonical_step_day_bucket_contributions"))
        }

    @Test
    fun syncStoresNonOverlappingGoogleStepIntervalsAndSumsDailySummary() =
        runBlocking {
            val fixture = Fixture()
            fixture.storeAccount(accessToken = "access-token", refreshToken = "refresh-token")
            fixture.client.fetchResults +=
                listOf(
                    fetchResult(
                        "steps",
                        stepsPoint(
                            name = "google-steps-1",
                            startAt = "2026-04-01T08:00:00Z",
                            endAt = "2026-04-01T09:00:00Z",
                            count = 1200,
                        ),
                    ),
                )
            fixture.client.fetchResults +=
                listOf(
                    fetchResult(
                        "steps",
                        stepsPoint(
                            name = "google-steps-2",
                            startAt = "2026-04-01T10:00:00Z",
                            endAt = "2026-04-01T11:00:00Z",
                            count = 800,
                        ),
                    ),
                )

            val first =
                fixture.provider.sync(
                    ProviderSyncRequest(
                        from = Instant.parse("2026-04-01T00:00:00Z"),
                        to = Instant.parse("2026-04-01T09:30:00Z"),
                        dataTypes = listOf("steps"),
                    ),
                    fixture.now,
                )
            val second =
                fixture.provider.sync(
                    ProviderSyncRequest(
                        from = Instant.parse("2026-04-01T09:30:00Z"),
                        to = Instant.parse("2026-04-02T00:00:00Z"),
                        dataTypes = listOf("steps"),
                    ),
                    fixture.now.plusSeconds(60),
                )

            assertEquals(1, first.batches.single().metricsCreated[StructuralMetricKinds.STEP_SAMPLES])
            assertEquals(1, second.batches.single().metricsCreated[StructuralMetricKinds.STEP_SAMPLES])
            assertEquals(0, second.batches.single().duplicateMetricsSkipped)
            assertEquals(2, fixture.databaseConfig.countRows("step_samples"))
            assertEquals(2000, fixture.databaseConfig.queryInt("SELECT SUM(value)::int FROM canonical_step_day_bucket_contributions"))
        }

    @Test
    fun syncRefreshesExpiredTokenAndRetriesUnauthorizedFetch() =
        runBlocking {
            val fixture = Fixture()
            fixture.storeAccount(
                accessToken = "expired-access",
                refreshToken = "refresh-token",
                expiresAt = fixture.now.minusSeconds(1),
            )
            fixture.client.refreshedAccessToken = "fresh-access"
            fixture.client.throwUnauthorizedOnce = true
            fixture.client.fetchResults += listOf(stepsFetchResult())

            val response =
                fixture.provider.sync(
                    ProviderSyncRequest(
                        from = Instant.parse("2026-04-01T00:00:00Z"),
                        to = Instant.parse("2026-04-02T00:00:00Z"),
                        dataTypes = listOf("steps"),
                    ),
                    fixture.now,
                )

            assertEquals(1, response.batches.size)
            assertEquals(2, fixture.client.refreshCalls)
            assertEquals(listOf("fresh-access", "fresh-access"), fixture.client.fetchAccessTokens)
            val accessCiphertext = fixture.databaseConfig.queryString("SELECT access_token_ciphertext FROM provider_oauth_accounts")!!
            assertEquals("fresh-access", TokenCipher(fixture.config.tokenEncryptionKey, GOOGLE_HEALTH_PROVIDER_CODE).decrypt(accessCiphertext))
        }

    @Test
    fun syncRecordsFailedRunForUpstreamFailure() =
        runBlocking {
            val fixture = Fixture()
            fixture.storeAccount(accessToken = "access-token", refreshToken = "refresh-token")
            fixture.client.nextFetchFailure =
                GoogleHealthHttpException(
                    "google_health_upstream_failed",
                    "Google Health failed with 429",
                )

            assertFailsWith<UpstreamProviderException> {
                fixture.provider.sync(
                    ProviderSyncRequest(
                        from = Instant.parse("2026-04-01T00:00:00Z"),
                        to = Instant.parse("2026-04-02T00:00:00Z"),
                        dataTypes = listOf("steps"),
                    ),
                    fixture.now,
                )
            }
            assertEquals("failed", fixture.databaseConfig.queryString("SELECT status FROM provider_sync_runs"))
        }

    private inner class Fixture(
        clientSecret: String = "client-secret",
    ) {
        val databaseConfig: DatabaseConfig = PostgresTestDatabase.config()
        val database: Database = openDatabase(databaseConfig)
        val now: Instant = Instant.parse("2026-04-20T10:00:00Z")
        val config =
            ProviderOAuthConfig(
                clientId = "client-id",
                clientSecret = clientSecret,
                redirectUri = "http://localhost:8080/api/v2/providers/google-health/oauth/callback",
                tokenEncryptionKey = TEST_TOKEN_ENCRYPTION_KEY,
                apiBaseUrl = "https://health.googleapis.com",
                oauthTokenUrl = "https://oauth2.googleapis.com/token",
                oauthAuthUrl = "https://accounts.google.com/o/oauth2/v2/auth",
            )
        val providerRepository = ProviderOAuthRepository(database)
        val client = FakeGoogleHealthClient()
        val provider =
            GoogleHealthProvider(
                config = config,
                repository = providerRepository,
                client = client,
                normalizer = GoogleHealthNormalizer(),
                syncPipeline =
                    me.aquitano.health.application.providersync.ProviderSyncPipeline(
                        store =
                            me.aquitano.health.application.providersync.OAuthProviderSyncStore(
                                repository = providerRepository,
                                ingestionService = ingestionService(database, realDerivedRebuildExecutor(database)),
                                tokenEncryptionKeys = mapOf(GOOGLE_HEALTH_PROVIDER_CODE to config.tokenEncryptionKey),
                            ),
                        clock = Clock.fixed(now, ZoneOffset.UTC),
                    ),
            )
        private val providerRegistry = HealthProviderRegistry(listOf(provider))
        val providerStatusService =
            ProviderStatusService(
                providerRegistry = providerRegistry,
                providerOAuthRepository = providerRepository,
            )
        val providerWorkflowService =
            ProviderWorkflowService(
                providerRegistry = providerRegistry,
                providerOAuthRepository = providerRepository,
                providerStatusService = providerStatusService,
                scheduledSyncRepository = ScheduledSyncRepository(database),
            )

        suspend fun storeAccount(
            providerInstanceId: String = "google-user-1",
            accessToken: String,
            refreshToken: String,
            expiresAt: Instant = now.plusSeconds(3600),
        ) {
            val cipher = TokenCipher(config.tokenEncryptionKey, GOOGLE_HEALTH_PROVIDER_CODE)
            providerRepository.upsertAccount(
                providerCode = GOOGLE_HEALTH_PROVIDER_CODE,
                providerUserId = providerInstanceId,
                providerInstanceId = providerInstanceId,
                accessTokenCiphertext = cipher.encrypt(accessToken),
                refreshTokenCiphertext = cipher.encrypt(refreshToken),
                tokenType = "Bearer",
                expiresAt = expiresAt,
                scope = GOOGLE_HEALTH_SCOPES.joinToString(" "),
                now = now,
            )
        }
    }

    private class FakeGoogleHealthClient : GoogleHealthClient {
        val fetchResults = ArrayDeque<List<GoogleHealthFetchResult>>()
        val fetchAccessTokens = mutableListOf<String>()
        val fetchRequests = mutableListOf<FetchRequest>()
        var refreshedAccessToken = "new-access"
        var refreshCalls = 0
        var throwUnauthorizedOnce = false
        var nextExchangeFailure: RuntimeException? = null
        var nextRefreshFailure: RuntimeException? = null
        var nextFetchFailure: RuntimeException? = null

        override suspend fun exchangeCode(
            code: String,
            now: Instant,
        ): RefreshedTokenSet {
            nextExchangeFailure?.let {
                nextExchangeFailure = null
                throw it
            }
            return RefreshedTokenSet(
                accessToken = "access-from-code",
                refreshToken = "refresh-from-code",
                tokenType = "Bearer",
                expiresAt = now.plusSeconds(3600),
                scope = GOOGLE_HEALTH_SCOPES.joinToString(" "),
            )
        }

        override suspend fun refreshToken(
            refreshToken: String,
            now: Instant,
        ): RefreshedTokenSet {
            refreshCalls += 1
            nextRefreshFailure?.let {
                nextRefreshFailure = null
                throw it
            }
            return RefreshedTokenSet(
                accessToken = refreshedAccessToken,
                refreshToken = refreshToken,
                tokenType = "Bearer",
                expiresAt = now.plusSeconds(3600),
                scope = GOOGLE_HEALTH_SCOPES.joinToString(" "),
            )
        }

        override suspend fun fetchDataPoints(
            accessToken: String,
            dataType: String,
            from: Instant,
            to: Instant,
            pageSize: Int,
        ): GoogleHealthFetchResult {
            fetchAccessTokens.add(accessToken)
            fetchRequests.add(FetchRequest(dataType, from, to))
            nextFetchFailure?.let {
                nextFetchFailure = null
                throw it
            }
            if (throwUnauthorizedOnce) {
                throwUnauthorizedOnce = false
                throw GoogleHealthUnauthorizedException("unauthorized")
            }
            val next = fetchResults.first()
            val result = next.first { it.dataType == dataType }
            if (dataType == next.last().dataType) fetchResults.removeFirst()
            return result
        }
    }

    private data class FetchRequest(
        val dataType: String,
        val from: Instant,
        val to: Instant,
    )

    private fun allMetricFetchResults(): List<GoogleHealthFetchResult> =
        listOf(
            stepsFetchResult(),
            fetchResult("sleep", sleepPoint()),
            fetchResult("heart-rate", heartRatePoint()),
            fetchResult("weight", weightPoint()),
            fetchResult("body-fat", bodyFatPoint()),
            fetchResult("heart-rate-variability", heartRateVariabilityPoint()),
            fetchResult("respiratory-rate-sleep-summary", respiratoryRatePoint()),
        )

    private fun stepsFetchResult(): GoogleHealthFetchResult = fetchResult("steps", stepsPoint())

    private fun fetchResult(
        dataType: String,
        point: JsonObject,
    ): GoogleHealthFetchResult {
        val payload =
            buildJsonObject {
                put("dataPoints", JsonArray(listOf(point)))
                put("nextPageToken", "")
            }
        return GoogleHealthFetchResult(
            dataType = dataType,
            pages = listOf(GoogleHealthPage(0, payload)),
            dataPoints = listOf(point),
        )
    }

    private fun stepsPoint(
        name: String = "google-steps-1",
        startAt: String = "2026-04-01T08:00:00Z",
        endAt: String = "2026-04-01T09:00:00Z",
        count: Int = 1200,
    ): JsonObject =
        buildJsonObject {
            put("name", name)
            putJsonObject("steps") {
                putJsonObject("interval") {
                    put("startTime", startAt)
                    put("endTime", endAt)
                }
                put("count", count.toString())
            }
        }

    private fun sleepPoint(): JsonObject =
        buildJsonObject {
            put("name", "google-sleep-1")
            putJsonObject("sleep") {
                putJsonObject("interval") {
                    put("startTime", "2026-03-31T22:00:00Z")
                    put("endTime", "2026-04-01T06:00:00Z")
                }
                putJsonArray("stages") {
                    add(
                        buildJsonObject {
                            put("type", "LIGHT")
                            put("startTime", "2026-03-31T22:00:00Z")
                            put("endTime", "2026-04-01T01:00:00Z")
                        },
                    )
                    add(
                        buildJsonObject {
                            put("type", "REM")
                            put("startTime", "2026-04-01T01:00:00Z")
                            put("endTime", "2026-04-01T02:00:00Z")
                        },
                    )
                }
                putJsonObject("summary") {
                    put("minutesInSleepPeriod", "480")
                    put("minutesAsleep", "450")
                }
            }
        }

    private fun heartRatePoint(): JsonObject =
        buildJsonObject {
            put("name", "google-hr-1")
            putJsonObject("heartRate") {
                putJsonObject("sampleTime") {
                    put("physicalTime", "2026-04-01T08:30:00Z")
                }
                put("beatsPerMinute", "62")
                putJsonObject("metadata") {
                    put("motionContext", "SEDENTARY")
                }
            }
        }

    private fun weightPoint(): JsonObject =
        buildJsonObject {
            put("name", "google-weight-1")
            putJsonObject("weight") {
                putJsonObject("sampleTime") {
                    put("physicalTime", "2026-04-01T07:00:00Z")
                }
                put("weightGrams", 82400.0)
            }
        }

    private fun bodyFatPoint(): JsonObject =
        buildJsonObject {
            put("name", "google-body-fat-1")
            putJsonObject("bodyFat") {
                putJsonObject("sampleTime") {
                    put("physicalTime", "2026-04-01T07:00:00Z")
                }
                put("percentage", 18.2)
            }
        }

    private fun heartRateVariabilityPoint(): JsonObject =
        buildJsonObject {
            put("name", "google-hrv-1")
            putJsonObject("heartRateVariability") {
                putJsonObject("sampleTime") {
                    put("physicalTime", "2026-04-01T03:15:00Z")
                }
                put("rootMeanSquareOfSuccessiveDifferencesMilliseconds", 42.7)
            }
        }

    private fun respiratoryRatePoint(): JsonObject =
        buildJsonObject {
            put("name", "google-respiratory-rate-1")
            putJsonObject("respiratoryRateSleepSummary") {
                putJsonObject("sampleTime") {
                    put("physicalTime", "2026-04-01T06:00:00Z")
                }
                putJsonObject("fullSleepStats") {
                    put("breathsPerMinute", 14.4)
                }
            }
        }
}
