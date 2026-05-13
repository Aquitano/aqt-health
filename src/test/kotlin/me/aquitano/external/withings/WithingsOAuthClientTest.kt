package me.aquitano.external.withings

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.http.parseQueryString
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import me.aquitano.health.infrastructure.config.WithingsConfig
import me.aquitano.health.shared.AppJson
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WithingsOAuthClientTest {
    private val now = Instant.parse("2026-04-20T10:00:00Z")

    @Test
    fun authorizationCodeExchangeSendsRequiredFormFieldsAndParsesResponse() = runBlocking {
        var form = emptyMap<String, List<String>>()
        val client = client { request ->
            form = request.formParameters()
            respondJson(
                """
                {
                  "status": 0,
                  "body": {
                    "userid": 363,
                    "access_token": "access-from-code",
                    "refresh_token": "refresh-from-code",
                    "expires_in": 10800,
                    "scope": "user.info,user.metrics",
                    "token_type": "Bearer"
                  }
                }
                """.trimIndent()
            )
        }

        val tokens = client.exchangeCode("auth-code", now)

        assertEquals(listOf("requesttoken"), form["action"])
        assertEquals(listOf("authorization_code"), form["grant_type"])
        assertEquals(listOf("client-id"), form["client_id"])
        assertEquals(listOf("client-secret"), form["client_secret"])
        assertEquals(listOf("auth-code"), form["code"])
        assertEquals(listOf("http://localhost:8080/api/v1/providers/withings/oauth/callback"), form["redirect_uri"])
        assertEquals("363", tokens.providerUserId)
        assertEquals("access-from-code", tokens.accessToken)
        assertEquals("refresh-from-code", tokens.refreshToken)
        assertEquals("Bearer", tokens.tokenType)
        assertEquals(now.plusSeconds(10800), tokens.expiresAt)
        assertEquals("user.info,user.metrics", tokens.scope)
    }

    @Test
    fun refreshExchangeSendsRefreshGrantAndPreservesExistingRefreshToken() = runBlocking {
        var form = emptyMap<String, List<String>>()
        val client = client { request ->
            form = request.formParameters()
            respondJson(
                """
                {
                  "status": 0,
                  "body": {
                    "access_token": "fresh-access",
                    "expires_in": 60
                  }
                }
                """.trimIndent()
            )
        }

        val tokens = client.refreshToken("existing-refresh", now)

        assertEquals(listOf("requesttoken"), form["action"])
        assertEquals(listOf("refresh_token"), form["grant_type"])
        assertEquals(listOf("existing-refresh"), form["refresh_token"])
        assertEquals("fresh-access", tokens.accessToken)
        assertEquals("existing-refresh", tokens.refreshToken)
        assertEquals("", tokens.providerUserId)
        assertEquals(now.plusSeconds(60), tokens.expiresAt)
    }

    @Test
    fun nonZeroWithingsStatusMapsToTokenRequestFailure() = runBlocking {
        val client = client {
            respondJson("""{"status": 293, "body": {}}""")
        }

        val error = assertFailsWith<WithingsHttpException> {
            client.exchangeCode("auth-code", now)
        }

        assertEquals("withings_token_request_failed", error.code)
    }

    @Test
    fun missingAccessTokenMapsToExplicitError() = runBlocking {
        val client = client {
            respondJson("""{"status": 0, "body": {"userid": 363, "refresh_token": "refresh"}}""")
        }

        val error = assertFailsWith<WithingsHttpException> {
            client.exchangeCode("auth-code", now)
        }

        assertEquals("withings_missing_access_token", error.code)
    }

    @Test
    fun missingRefreshTokenOnAuthorizationCodeExchangeMapsToExplicitError() = runBlocking {
        val client = client {
            respondJson("""{"status": 0, "body": {"userid": 363, "access_token": "access"}}""")
        }

        val error = assertFailsWith<WithingsHttpException> {
            client.exchangeCode("auth-code", now)
        }

        assertEquals("withings_missing_refresh_token", error.code)
    }

    @Test
    fun missingUserIdOnAuthorizationCodeExchangeMapsToExplicitError() = runBlocking {
        val client = client {
            respondJson("""{"status": 0, "body": {"access_token": "access", "refresh_token": "refresh"}}""")
        }

        val error = assertFailsWith<WithingsHttpException> {
            client.exchangeCode("auth-code", now)
        }

        assertEquals("withings_missing_userid", error.code)
    }

    @Test
    fun httpFailureMapsToTokenRequestFailure() = runBlocking {
        val client = client {
            respond(
                content = """{"status": 0, "body": {}}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val error = assertFailsWith<WithingsHttpException> {
            client.exchangeCode("auth-code", now)
        }

        assertEquals("withings_token_request_failed", error.code)
    }

    private fun client(handler: MockRequestHandler): KtorWithingsOAuthClient {
        val httpClient = HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) {
                json(AppJson)
            }
        }
        return KtorWithingsOAuthClient(httpClient, config())
    }

    private fun MockRequestHandleScope.respondJson(content: String) =
        respond(
            content = content,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )

    private fun HttpRequestData.formParameters(): Map<String, List<String>> =
        parseQueryString(bodyText()).entries()
            .associate { it.key to it.value }

    private fun HttpRequestData.bodyText(): String =
        when (val content = body) {
            is OutgoingContent.ByteArrayContent -> String(content.bytes())
            is OutgoingContent.NoContent -> ""
            else -> content.toString()
        }

    private fun config(): WithingsConfig =
        WithingsConfig(
            clientId = "client-id",
            clientSecret = "client-secret",
            redirectUri = "http://localhost:8080/api/v1/providers/withings/oauth/callback",
            tokenEncryptionKey = "test-token-encryption-key-with-32-bytes",
            oauthTokenUrl = "https://wbsapi.withings.net/v2/oauth2",
            oauthAuthUrl = "https://account.withings.com/oauth2_user/authorize2",
        )
}
