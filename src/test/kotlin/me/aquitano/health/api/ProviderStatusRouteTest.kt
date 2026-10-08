package me.aquitano.health.api

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.aquitano.health.infrastructure.config.DatabaseConfig
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.authorized
import me.aquitano.health.test.configureTestApplication
import me.aquitano.health.test.execute
import me.aquitano.health.test.googleHealthTestConfig
import me.aquitano.health.test.jsonBody
import me.aquitano.health.test.queryString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ProviderStatusRouteTest : PostgresIntegrationTest() {
    @Test
    fun unconfiguredProviderReportsConfigureAction() =
        testApplication {
            configureTestApplication(*googleHealthTestConfig(withClientSecret = false))

            val response =
                client.get("/api/v2/providers/google-health/status") {
                    authorized()
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val status = response.jsonBody()
            assertEquals("google-health", status.string("providerCode"))
            assertEquals("false", status["configured"].toString())
            assertEquals("false", status["connected"].toString())
            assertEquals("false", status["needsAuthentication"].toString())
            assertEquals("false", status["canSync"].toString())
            assertEquals("configure", status.string("nextAction"))
            assertEquals(0, status["accounts"]!!.jsonArray.size)
        }

    @Test
    fun configuredButUnconnectedProviderReportsConnectAction() =
        testApplication {
            configureTestApplication(*googleHealthTestConfig())

            val response =
                client.get("/api/v2/providers/google-health/status") {
                    authorized()
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val status = response.jsonBody()
            assertEquals("true", status["configured"].toString())
            assertEquals("false", status["connected"].toString())
            assertEquals("true", status["needsAuthentication"].toString())
            assertEquals("false", status["canSync"].toString())
            assertEquals("connect", status.string("nextAction"))
        }

    @Test
    fun connectedProviderReportsValidAccountAndLastSync() =
        testApplication {
            val database = configureTestApplication(*googleHealthTestConfig())
            client.get("/api/v2/admin/health")
            database.insertGoogleAccount(expiresAt = "2099-01-01T00:00:00Z")
            database.insertSyncRun(finishedAt = "2026-05-15T11:00:00Z")
            database.insertSyncRun(finishedAt = null)

            val response =
                client.get("/api/v2/providers/status") {
                    authorized()
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val google =
                response
                    .jsonBody()
                    .getValue("items")
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it.string("providerCode") == "google-health" }
            assertEquals("true", google["connected"].toString())
            assertEquals("true", google["canSync"].toString())
            assertEquals("false", google["needsAuthentication"].toString())
            assertEquals("sync", google.string("nextAction"))
            val account = google["accounts"]!!.jsonArray.single().jsonObject
            assertEquals("google-health-me", account.string("providerInstanceId"))
            assertEquals("connected", account.string("status"))
            assertEquals("valid", account.string("tokenStatus"))
            assertEquals("2026-05-15T10:00:00Z", account.string("connectedAt"))
            assertEquals("2026-05-15T11:00:00Z", account.string("lastSyncAt"))
        }

    @Test
    fun connectedExpiredProviderCanStillSyncWithRefreshToken() =
        testApplication {
            val database = configureTestApplication(*googleHealthTestConfig())
            client.get("/api/v2/admin/health")
            database.insertGoogleAccount(expiresAt = "2000-01-01T00:00:00Z")

            val response =
                client.get("/api/v2/providers/google_health/status") {
                    authorized()
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val status = response.jsonBody()
            assertEquals("true", status["connected"].toString())
            assertEquals("true", status["canSync"].toString())
            assertEquals("false", status["needsAuthentication"].toString())
            assertEquals("sync", status.string("nextAction"))
            val account = status["accounts"]!!.jsonArray.single().jsonObject
            assertEquals("connected", account.string("status"))
            assertEquals("expired", account.string("tokenStatus"))
        }

    @Test
    fun needsReauthProviderReportsReconnectAction() =
        testApplication {
            val database = configureTestApplication(*googleHealthTestConfig())
            client.get("/api/v2/admin/health")
            database.insertGoogleAccount(
                expiresAt = "2099-01-01T00:00:00Z",
                accountStatus = "needs_reauth",
                lastAuthErrorCode = "google_health_needs_reauth",
            )

            val response =
                client.get("/api/v2/providers/google-health/status") {
                    authorized()
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val status = response.jsonBody()
            assertEquals("false", status["connected"].toString())
            assertEquals("false", status["canSync"].toString())
            assertEquals("true", status["needsAuthentication"].toString())
            assertEquals("reconnect", status.string("nextAction"))
            val account = status["accounts"]!!.jsonArray.single().jsonObject
            assertEquals("needs_reauth", account.string("status"))
            assertEquals("google_health_needs_reauth", account.string("lastAuthErrorCode"))
        }

    @Test
    fun providerWithSyncableAndNeedsReauthAccountsStillReportsSyncAction() =
        testApplication {
            val database = configureTestApplication(*googleHealthTestConfig())
            client.get("/api/v2/admin/health")
            database.insertGoogleAccount(
                providerUserId = "syncable-user",
                providerInstanceId = "google-health-syncable",
                expiresAt = "2099-01-01T00:00:00Z",
            )
            database.insertGoogleAccount(
                providerUserId = "reauth-user",
                providerInstanceId = "google-health-reauth",
                expiresAt = "2099-01-01T00:00:00Z",
                accountStatus = "needs_reauth",
                lastAuthErrorCode = "google_health_needs_reauth",
            )

            val response =
                client.get("/api/v2/providers/google-health/status") {
                    authorized()
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val status = response.jsonBody()
            assertEquals("true", status["connected"].toString())
            assertEquals("true", status["canSync"].toString())
            assertEquals("false", status["needsAuthentication"].toString())
            assertEquals("sync", status.string("nextAction"))
            val accounts = status["accounts"]!!.jsonArray.map { it.jsonObject.string("status") }.toSet()
            assertEquals(setOf("connected", "needs_reauth"), accounts)
        }

    @Test
    fun disconnectedProviderReportsConnectAction() =
        testApplication {
            val database = configureTestApplication(*googleHealthTestConfig())
            client.get("/api/v2/admin/health")
            database.insertGoogleAccount(
                expiresAt = "2099-01-01T00:00:00Z",
                accountStatus = "disconnected",
                accessTokenCiphertext = "",
                refreshTokenCiphertext = "",
                disconnectedAt = "2026-05-16T10:00:00Z",
            )

            val response =
                client.get("/api/v2/providers/google-health/status") {
                    authorized()
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val status = response.jsonBody()
            assertEquals("false", status["connected"].toString())
            assertEquals("false", status["canSync"].toString())
            assertEquals("connect", status.string("nextAction"))
            val account = status["accounts"]!!.jsonArray.single().jsonObject
            assertEquals("disconnected", account.string("status"))
            assertEquals("missing", account.string("tokenStatus"))
            assertEquals("2026-05-16T10:00:00Z", account.string("disconnectedAt"))
        }

    @Test
    fun providerAccountLifecycleRoutesListDisconnectAndReconnect() =
        testApplication {
            val database = configureTestApplication(*googleHealthTestConfig())
            client.get("/api/v2/admin/health")
            database.insertGoogleAccount(expiresAt = "2099-01-01T00:00:00Z")

            val listResponse =
                client.get("/api/v2/providers/google-health/accounts") {
                    authorized()
                }
            assertEquals(HttpStatusCode.OK, listResponse.status)
            val listBody = listResponse.jsonBody()
            assertEquals("google-health", listBody.string("provider"))
            assertEquals(
                "google-health-me",
                listBody["accounts"]!!
                    .jsonArray
                    .single()
                    .jsonObject
                    .string("providerInstanceId"),
            )

            val getResponse =
                client.get("/api/v2/providers/google-health/accounts/google-health-me") {
                    authorized()
                }
            assertEquals(HttpStatusCode.OK, getResponse.status)
            assertEquals("connected", getResponse.jsonBody().string("status"))

            val disconnectResponse =
                client.post("/api/v2/providers/google-health/accounts/google-health-me/disconnect") {
                    authorized()
                }
            assertEquals(HttpStatusCode.OK, disconnectResponse.status)
            assertEquals("disconnected", disconnectResponse.jsonBody().string("status"))
            assertEquals("", database.queryString("SELECT access_token_ciphertext FROM provider_oauth_accounts"))
            assertEquals("", database.queryString("SELECT refresh_token_ciphertext FROM provider_oauth_accounts"))

            val reconnectResponse =
                client.post("/api/v2/providers/google-health/accounts/google-health-me/reconnect") {
                    authorized()
                }
            assertEquals(HttpStatusCode.OK, reconnectResponse.status)
            val reconnectBody = reconnectResponse.jsonBody()
            assertEquals("google_health", reconnectBody.string("provider"))
            assertContains(reconnectBody.string("authorizationUrl"), "state=")
        }

    @Test
    fun unknownProviderStatusReturnsNotFound() =
        testApplication {
            configureTestApplication(*googleHealthTestConfig())

            val response =
                client.get("/api/v2/providers/not-real/status") {
                    authorized()
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    private fun DatabaseConfig.insertGoogleAccount(
        providerUserId: String = "google-health-me",
        providerInstanceId: String = "google-health-me",
        expiresAt: String,
        accountStatus: String = "connected",
        accessTokenCiphertext: String = "access-ciphertext",
        refreshTokenCiphertext: String = "refresh-ciphertext",
        disconnectedAt: String? = null,
        lastAuthErrorCode: String? = null,
    ) = execute(
        """
        INSERT INTO provider_oauth_accounts (
            provider_code,
            provider_user_id,
            provider_instance_id,
            access_token_ciphertext,
            refresh_token_ciphertext,
            token_type,
            expires_at,
            scope,
            account_status,
            connected_at,
            disconnected_at,
            last_auth_error_code,
            created_at,
            updated_at
        ) VALUES (
            'google_health',
            '$providerUserId',
            '$providerInstanceId',
            '$accessTokenCiphertext',
            '$refreshTokenCiphertext',
            'Bearer',
            '$expiresAt',
            'scope',
            '$accountStatus',
            '2026-05-15T10:00:00Z',
            ${disconnectedAt?.let { "'$it'" } ?: "NULL"},
            ${lastAuthErrorCode?.let { "'$it'" } ?: "NULL"},
            '2026-05-15T10:00:00Z',
            '2026-05-15T10:00:00Z'
        )
        """.trimIndent(),
    )

    private fun DatabaseConfig.insertSyncRun(finishedAt: String?) =
        execute(
            """
            INSERT INTO provider_sync_runs (
                provider_code,
                provider_instance_id,
                requested_from,
                requested_to,
                status,
                started_at,
                finished_at,
                error_message
            ) VALUES (
                'google_health',
                'google-health-me',
                '2026-05-15T09:00:00Z',
                '2026-05-15T10:00:00Z',
                '${if (finishedAt == null) "running" else "processed"}',
                '2026-05-15T10:30:00Z',
                ${finishedAt?.let { "'$it'" } ?: "NULL"},
                NULL
            )
            """.trimIndent(),
        )

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
}
