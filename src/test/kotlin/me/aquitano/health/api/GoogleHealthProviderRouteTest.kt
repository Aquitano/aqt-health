package me.aquitano.health.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.aquitano.health.shared.AppJson
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.authorized
import me.aquitano.health.test.configureTestApplication
import me.aquitano.health.test.errorCode
import me.aquitano.health.test.googleHealthTestConfig
import me.aquitano.health.test.jsonBody
import me.aquitano.health.test.queryString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoogleHealthProviderRouteTest : PostgresIntegrationTest() {
    @Test
    fun oauthStartReturnsAuthorizationUrlWithReadonlyScopes() =
        testApplication {
            configureTestApplication(*googleHealthTestConfig())

            val response =
                client.get("/api/v2/providers/google-health/oauth/start") {
                    authorized()
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val url = response.jsonBody()["authorizationUrl"]!!.jsonPrimitive.content
            assertTrue(url.contains("access_type=offline"))
            assertTrue(url.contains("prompt=consent"))
            assertTrue(url.contains("googlehealth.activity_and_fitness.readonly"))
            assertTrue(url.contains("googlehealth.health_metrics_and_measurements.readonly"))
            assertTrue(url.contains("googlehealth.sleep.readonly"))
        }

    @Test
    fun syncRejectsInvalidDateRange() =
        testApplication {
            configureTestApplication(*googleHealthTestConfig())

            val response =
                client.post("/api/v2/providers/google-health/sync-jobs") {
                    authorized()
                    contentType(ContentType.Application.Json)
                    setBody("""{"from":"2026-04-02T00:00:00Z","to":"2026-04-01T00:00:00Z"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("validation_failed", response.errorCode())
        }

    @Test
    fun syncRangeBeyondTheCeilingIsRejected() =
        testApplication {
            configureTestApplication(*googleHealthTestConfig())

            val response =
                client.post("/api/v2/providers/google-health/sync-jobs") {
                    authorized()
                    contentType(ContentType.Application.Json)
                    setBody("""{"from":"1970-01-01T00:00:00Z","to":"2026-01-01T00:00:00Z","dataTypes":["steps"]}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("validation_failed", response.errorCode())
        }

    @Test
    fun syncJobMultiYearRangeWithinTheCeilingIsAcceptedAndPollable() =
        testApplication {
            val database = configureTestApplication(*googleHealthTestConfig())

            val startResponse =
                client.post("/api/v2/providers/google-health/sync-jobs") {
                    authorized()
                    contentType(ContentType.Application.Json)
                    setBody("""{"from":"2023-06-01T00:00:00Z","to":"2026-01-01T00:00:00Z","dataTypes":["steps"]}""")
                }

            assertEquals(HttpStatusCode.Accepted, startResponse.status)
            val jobId = startResponse.jsonBody()["jobId"]!!.jsonPrimitive.content

            val statusResponse =
                client.get("/api/v2/providers/google-health/sync-jobs/$jobId") {
                    authorized()
                }

            assertEquals(HttpStatusCode.OK, statusResponse.status)
            val statusBody = statusResponse.jsonBody()
            assertEquals(jobId, statusBody["jobId"]!!.jsonPrimitive.content)
            assertEquals("google-health", statusBody["providerCode"]!!.jsonPrimitive.content)
            // Stored as the internal code so provider_sync_jobs correlates with scheduled_syncs and
            // provider_sync_runs; the wire code is restored on read.
            assertEquals("google_health", database.queryString("SELECT provider_code FROM provider_sync_jobs"))
        }

    @Test
    fun syncRejectsInvalidPageSize() =
        testApplication {
            configureTestApplication(*googleHealthTestConfig())

            val response =
                client.post("/api/v2/providers/google-health/sync-jobs") {
                    authorized()
                    contentType(ContentType.Application.Json)
                    setBody("""{"from":"2026-04-01T00:00:00Z","to":"2026-04-02T00:00:00Z","pageSize":0}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("validation_failed", response.errorCode())
        }

    @Test
    fun missingProviderConfigReturnsInternalServerErrorWithoutLeakingConfigFields() =
        testApplication {
            configureTestApplication(*googleHealthTestConfig(withClientSecret = false))

            val response =
                client.get("/api/v2/providers/google-health/oauth/start") {
                    authorized()
                    header(HttpHeaders.XRequestId, "google-config-test")
                }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val bodyText = response.bodyAsText()
            val error = AppJson.parseToJsonElement(bodyText).jsonObject["error"]!!.jsonObject
            assertEquals("google_health_not_configured", error["code"]!!.jsonPrimitive.content)
            assertEquals("Provider is not configured", error["message"]!!.jsonPrimitive.content)
            assertEquals("google-config-test", error["requestId"]!!.jsonPrimitive.content)
            assertFalse(bodyText.contains("googleHealth.clientSecret"))
        }
}
