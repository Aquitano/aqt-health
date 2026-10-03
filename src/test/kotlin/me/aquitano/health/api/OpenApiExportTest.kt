package me.aquitano.health.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.aquitano.health.shared.AppJson
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.PostgresTestDatabase
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OpenApiExportTest : PostgresIntegrationTest() {
    @Test
    fun exportOpenApiSpec() =
        testApplication {
            configureExportApplication()

            val response = client.get("/openapi")

            assertEquals(HttpStatusCode.OK, response.status)
            val specText = response.bodyAsText()
            val schemas =
                AppJson
                    .parseToJsonElement(specText)
                    .jsonObject["components"]!!
                    .jsonObject["schemas"]!!
                    .jsonObject

            assertEquals(
                setOf("ErrorResponse", "ErrorBody", "ErrorDetail"),
                schemas.keys.filter { it.startsWith("Error") }.toSet(),
                "Reusable error schemas must not be duplicated by OpenAPI inference",
            )

            val summariesItemRef =
                schemas["ScheduledSyncRunResponse"]!!
                    .jsonObject["properties"]!!
                    .jsonObject["summaries"]!!
                    .jsonObject["items"]!!
                    .jsonObject["\$ref"]!!
                    .jsonPrimitive.content
            val itemSchema = schemas[summariesItemRef.substringAfterLast('/')]!!.jsonObject
            assertEquals("object", itemSchema["type"]!!.jsonPrimitive.content)

            val nullableSummaryRef =
                schemas["ProviderSyncJobStatusResponse"]!!
                    .jsonObject["properties"]!!
                    .jsonObject["summary"]!!
                    .jsonObject["\$ref"]!!
                    .jsonPrimitive.content
            val nullableSummary = schemas[nullableSummaryRef.substringAfterLast('/')]!!.jsonObject
            assertEquals(
                setOf("object", "null"),
                nullableSummary["type"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
            )
            assertEquals(itemSchema["properties"], nullableSummary["properties"])
            assertEquals(itemSchema["required"], nullableSummary["required"])

            val scalarSampleProperties =
                schemas["ScalarSampleResponse"]!!
                    .jsonObject["properties"]!!
                    .jsonObject
            assertEquals(
                "date-time",
                scalarSampleProperties["measuredAt"]!!.jsonObject["format"]!!.jsonPrimitive.content,
            )

            val readMetaProperties =
                schemas["ReadResponseMeta"]!!
                    .jsonObject["properties"]!!
                    .jsonObject
            assertNotNull(readMetaProperties["nextCursor"])

            val output =
                System
                    .getProperty("aqtHealth.openapi.output")
                    ?.let(Path::of)
                    ?: return@testApplication
            output.parent?.createDirectories()
            output.writeText(specText)
        }

    private fun ApplicationTestBuilder.configureExportApplication() {
        val dbConfig = PostgresTestDatabase.config()
        environment {
            config =
                MapApplicationConfig(
                    "ktor.application.modules.size" to "1",
                    "ktor.application.modules.0" to "me.aquitano.health.api.ApplicationKt.module",
                    *PostgresTestDatabase.ktorConfigEntries(dbConfig),
                    "aqtHealth.auth.bootstrapClientName" to "test-client",
                    "aqtHealth.auth.bootstrapApiKey" to "test-key",
                )
        }
    }
}
