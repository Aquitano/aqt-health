@file:OptIn(ExperimentalKtorApi::class)

package me.aquitano.health.api

import io.ktor.openapi.*
import io.ktor.utils.io.*
import me.aquitano.health.shared.AppJson
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KType

internal const val BearerApiKeySecurityScheme = "bearerApiKey"

internal fun openApiSchemaInference(): JsonSchemaInference {
    val inference = KotlinxSerializerJsonSchemaInference(AppJson.serializersModule)
    val schemas = ConcurrentHashMap<KType, JsonSchema>()
    // Ktor 3.6 compares annotation elements by identity when deduplicating schemas.
    return JsonSchemaInference { type -> schemas.computeIfAbsent(type, inference::buildSchema) }
}

internal fun openApiInfo(): OpenApiInfo =
    OpenApiInfo(
        title = "aqt-health",
        version = "0.0.1",
        description = "Personal health data hub API for normalized ingestion, provider OAuth and sync workflows, metric reads, and local administration.",
        contact = null,
    )

internal fun openApiBaseDoc(): OpenApiDoc =
    OpenApiDoc(
        openapi = OpenApiDoc.OPENAPI_VERSION,
        info = openApiInfo(),
        servers =
            listOf(
                Server(url = "/", description = "Same-origin deployment"),
                Server(
                    url = "http://localhost:8080",
                    description = "Local development",
                ),
            ),
        paths = emptyMap(),
        webhooks = emptyMap(),
        components = openApiComponents(),
        security = listOf(mapOf(BearerApiKeySecurityScheme to emptyList())),
        tags =
            listOf(
                Tag("Admin", "Health checks and ingestion administration."),
                Tag(
                    "Ingestion",
                    "Normalized health batch ingestion for trusted clients and provider adapters.",
                ),
                Tag(
                    "Providers",
                    "Provider discovery, OAuth connection, status, and synchronization workflows.",
                ),
                Tag(
                    "Read",
                    "Metric catalog and read endpoints for health data queries.",
                ),
            ),
        externalDocs = null,
        extensions = emptyMap(),
    )

private fun openApiComponents(): Components =
    Components(
        schemas = emptyMap(),
        securitySchemes =
            mapOf(
                BearerApiKeySecurityScheme to
                    ReferenceOr.Value<SecurityScheme>(
                        HttpSecurityScheme(
                            scheme = "bearer",
                            bearerFormat = "API key",
                            description = "Use `Authorization: Bearer <api-key>` with an API key registered in aqt-health.",
                        ),
                    ),
            ),
        examples =
            mapOf(
                "ErrorResponse" to
                    ReferenceOr.Value(
                        validationErrorExample(),
                    ),
            ),
    )
