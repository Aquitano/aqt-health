package me.aquitano.health.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.aquitano.health.shared.AppJson
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.authorized
import me.aquitano.health.test.configureTestApplication
import me.aquitano.health.test.jsonBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApplicationTest : PostgresIntegrationTest() {
    @Test
    fun healthEndpointResponds() =
        testApplication {
            configureTestApplication()
            val response = client.get("/api/v2/admin/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ok", response.jsonBody()["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun requestIdIsEchoedFromHeaderOrGeneratedWhenAbsent() =
        testApplication {
            configureTestApplication()

            val withId =
                client.get("/api/v2/admin/health") {
                    header(HttpHeaders.XRequestId, "test-request-123")
                }
            assertEquals("test-request-123", withId.headers[HttpHeaders.XRequestId])

            val withoutId = client.get("/api/v2/admin/health")
            val generated = withoutId.headers[HttpHeaders.XRequestId]
            assertNotNull(generated)
            assertTrue(generated.isNotBlank())
        }

    @Test
    fun unauthorizedResponseIncludesErrorCodeAndRequestId() =
        testApplication {
            configureTestApplication()

            val response =
                client.get("/api/v2/admin/ingestion/batches") {
                    header(HttpHeaders.XRequestId, "test-request-123")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("test-request-123", response.headers[HttpHeaders.XRequestId])
            val error = response.jsonBody()["error"]!!.jsonObject
            assertEquals("unauthorized", error["code"]!!.jsonPrimitive.content)
            assertEquals("Missing or invalid API key", error["message"]!!.jsonPrimitive.content)
            assertEquals("test-request-123", error["requestId"]!!.jsonPrimitive.content)
        }

    @Test
    fun validationErrorDetailsIncludeMachineReadableCodes() =
        testApplication {
            configureTestApplication()

            val response =
                client.get("/api/v2/dashboard/summary?fromDate=not-a-date&toDate=2026-04-02") {
                    authorized()
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val error = response.jsonBody()["error"]!!.jsonObject
            val detail = error["details"]!!.jsonArray.first().jsonObject
            assertEquals("validation_failed", error["code"]!!.jsonPrimitive.content)
            assertEquals("invalid_format", detail["code"]!!.jsonPrimitive.content)
            assertEquals("fromDate", detail["field"]!!.jsonPrimitive.content)
        }

    @Test
    fun openApiUsesSecuritySchemeInsteadOfAuthorizationParameter() =
        testApplication {
            configureTestApplication()

            val specText = client.get("/openapi").bodyAsText()
            val spec = AppJson.parseToJsonElement(specText).jsonObject
            val paths = spec["paths"]!!.jsonObject

            fun securityOf(path: String) =
                paths[path]!!
                    .jsonObject["get"]!!
                    .jsonObject["security"]!!
                    .jsonArray
                    .first()
                    .jsonObject

            assertNotNull(spec["components"]!!.jsonObject["securitySchemes"]!!.jsonObject["bearerApiKey"])
            assertEquals(setOf("bearerApiKey"), securityOf("/api/v2/providers").keys)
            assertTrue(securityOf("/api/v2/admin/health").isEmpty())
            assertTrue(securityOf("/api/v2/providers/{providerCode}/oauth/callback").isEmpty())
            assertFalse(specText.contains("\"name\":\"Authorization\""))
            assertFalse(specText.contains("\"name\": \"Authorization\""))
        }
}
