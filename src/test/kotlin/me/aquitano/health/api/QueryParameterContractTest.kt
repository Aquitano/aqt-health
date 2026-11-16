package me.aquitano.health.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.aquitano.health.shared.AppJson
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.authorized
import me.aquitano.health.test.configureTestApplication
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals

/** Unknown query parameters are rejected, so every parameter the OpenAPI spec documents must be read by its handler. */
class QueryParameterContractTest : PostgresIntegrationTest() {
    @Test
    fun everyDocumentedQueryParameterIsAccepted() =
        testApplication {
            configureTestApplication()
            val paths = AppJson.parseToJsonElement(client.get("/openapi").bodyAsText()).jsonObject["paths"]!!.jsonObject

            val rejected =
                paths.flatMap { (path, item) ->
                    val query =
                        item.jsonObject["get"]
                            ?.jsonObject["parameters"]
                            ?.jsonArray
                            .orEmpty()
                            .map { it.jsonObject }
                            .filter { it["in"]!!.jsonPrimitive.content == "query" }
                    val required = query.filter { it["required"]?.jsonPrimitive?.boolean == true }
                    query
                        .filter { parameter ->
                            val queryString = (required + parameter).distinctBy { it.name() }.joinToString("&") { "${it.name()}=${it.exampleValue()}" }
                            client.get("${concrete(path)}?$queryString") { authorized() }.bodyAsText().contains("unknown_parameter")
                        }.map { "$path ${it.name()}" }
                }

            assertEquals(emptyList(), rejected)
        }

    private fun JsonObject.name() = this["name"]!!.jsonPrimitive.content

    private fun JsonObject.exampleValue(): String {
        val example = this["schema"]?.jsonObject["example"]?.jsonPrimitive?.content ?: "1"
        return URLEncoder.encode(example, Charsets.UTF_8)
    }

    private fun concrete(path: String) =
        path
            .replace("{metricType}", "heart_rate")
            .replace("{providerCode}", "withings")
            .replace(Regex("\\{[^}]+}"), "1")
}
