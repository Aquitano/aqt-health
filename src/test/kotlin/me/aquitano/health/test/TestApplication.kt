package me.aquitano.health.test

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.aquitano.health.infrastructure.config.DatabaseConfig
import me.aquitano.health.shared.AppJson

const val TEST_API_KEY = "test-key"

const val TEST_TOKEN_ENCRYPTION_KEY = "test-token-encryption-key-with-32-bytes"

/** Boots the real application module against [database]; [extraConfig] entries override the defaults. */
fun ApplicationTestBuilder.configureTestApplication(
    vararg extraConfig: Pair<String, String>,
    database: DatabaseConfig = PostgresTestDatabase.config(),
): DatabaseConfig {
    environment {
        config =
            MapApplicationConfig(
                "ktor.application.modules.size" to "1",
                "ktor.application.modules.0" to "me.aquitano.health.api.ApplicationKt.module",
                *PostgresTestDatabase.ktorConfigEntries(database),
                "aqtHealth.auth.bootstrapClientName" to "test-client",
                "aqtHealth.auth.bootstrapApiKey" to TEST_API_KEY,
                *extraConfig,
            )
    }
    return database
}

fun googleHealthTestConfig(withClientSecret: Boolean = true): Array<Pair<String, String>> = providerTestConfig("aqtHealth.googleHealth", "google-health", "client-id", "client-secret".takeIf { withClientSecret })

fun withingsTestConfig(withClientSecret: Boolean = true): Array<Pair<String, String>> = providerTestConfig("aqtHealth.withings", "withings", "withings-client-id", "withings-client-secret".takeIf { withClientSecret })

private fun providerTestConfig(
    prefix: String,
    routeCode: String,
    clientId: String,
    clientSecret: String?,
): Array<Pair<String, String>> =
    listOfNotNull(
        "$prefix.clientId" to clientId,
        clientSecret?.let { "$prefix.clientSecret" to it },
        "$prefix.tokenEncryptionKey" to TEST_TOKEN_ENCRYPTION_KEY,
        "$prefix.redirectUri" to "http://localhost:8080/api/v2/providers/$routeCode/oauth/callback",
        "$prefix.apiBaseUrl" to "https://$routeCode.test",
        "$prefix.oauthTokenUrl" to "https://$routeCode.test/oauth/token",
        "$prefix.oauthAuthUrl" to "https://$routeCode.test/oauth/authorize",
    ).toTypedArray()

fun HttpRequestBuilder.authorized() = bearerAuth(TEST_API_KEY)

suspend fun HttpResponse.jsonBody(): JsonObject = AppJson.parseToJsonElement(bodyAsText()).jsonObject

suspend fun HttpResponse.errorCode(): String = jsonBody()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
