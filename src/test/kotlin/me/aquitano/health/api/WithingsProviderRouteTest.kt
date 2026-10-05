package me.aquitano.health.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.aquitano.health.shared.AppJson
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.authorized
import me.aquitano.health.test.configureTestApplication
import me.aquitano.health.test.jsonBody
import me.aquitano.health.test.withingsTestConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WithingsProviderRouteTest : PostgresIntegrationTest() {
    @Test
    fun oauthStartReturnsAuthorizationUrlWithDefaultScopes() =
        testApplication {
            configureTestApplication(*withingsTestConfig())

            val response =
                client.get("/api/v2/providers/withings/oauth/start") {
                    authorized()
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val url = response.jsonBody()["authorizationUrl"]!!.jsonPrimitive.content
            assertTrue(url.startsWith("https://withings.test/oauth/authorize?"))
            assertTrue(url.contains("response_type=code"))
            assertTrue(url.contains("client_id=withings-client-id"))
            assertTrue(url.contains("scope=user.info%2Cuser.metrics%2Cuser.activity"))
        }

    @Test
    fun oauthCallbackUsesProviderPathCodeNotAuthorizationCode() =
        testApplication {
            configureTestApplication(*withingsTestConfig())

            val response =
                client.get(
                    "/api/v2/providers/withings/oauth/callback?code=authorization-code&state=missing-state",
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val bodyText = response.bodyAsText()
            assertTrue(bodyText.contains("state"))
            assertFalse(bodyText.contains("Provider 'authorization-code' not found"))
        }

    @Test
    fun missingProviderConfigReturnsInternalServerErrorWithoutLeakingConfigFields() =
        testApplication {
            configureTestApplication(*withingsTestConfig(withClientSecret = false))

            val response =
                client.get("/api/v2/providers/withings/oauth/start") {
                    authorized()
                    header(HttpHeaders.XRequestId, "withings-config-test")
                }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val bodyText = response.bodyAsText()
            val error = AppJson.parseToJsonElement(bodyText).jsonObject["error"]!!.jsonObject
            assertEquals("withings_not_configured", error["code"]!!.jsonPrimitive.content)
            assertEquals("Provider is not configured", error["message"]!!.jsonPrimitive.content)
            assertEquals("withings-config-test", error["requestId"]!!.jsonPrimitive.content)
            assertFalse(bodyText.contains("withings.clientSecret"))
        }
}
