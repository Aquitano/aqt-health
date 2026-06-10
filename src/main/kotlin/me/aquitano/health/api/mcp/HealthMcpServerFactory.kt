package me.aquitano.health.api.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.aquitano.health.api.ApplicationServices

fun createHealthMcpServer(services: ApplicationServices): Server {
    val tools = HealthMcpTools(services)
    val server = Server(
        serverInfo = Implementation(
            name = "aqt-health",
            version = "0.0.1",
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
    )

    server.addTool(
        name = "health_catalog",
        description = "List supported health metric families, read modes, query parameters, and provider mappings.",
        inputSchema = objectSchema(),
        toolAnnotations = readOnlyAnnotations("Health metric catalog"),
    ) {
        tools.catalog()
    }

    server.addTool(
        name = "health_day",
        description = "Return a module-based health rollup for one local day.",
        inputSchema = objectSchema(
            required = listOf("date", "modules"),
            properties = buildJsonObject {
                stringProperty("date", "ISO-8601 local date or today.")
                putJsonObject("modules") {
                    put("description", "Health day modules to include.")
                    putJsonArray("oneOf") {
                        addObjectSchema("string")
                        addArraySchema("string", listOf("steps", "heartRate", "weight", "sleep"))
                    }
                }
                stringProperty("timezone", "IANA timezone. Defaults to UTC.")
                sourceFilterProperties()
                booleanProperty("includeSource", "Include source provider metadata when supported.")
            },
        ),
        toolAnnotations = readOnlyAnnotations("Health day rollup"),
    ) { request ->
        tools.healthDay(request.arguments.toArguments())
    }

    server.addTool(
        name = "dashboard_summary",
        description = "Return period summary values for dashboard-style health metrics.",
        inputSchema = objectSchema(
            required = listOf("fromDate", "toDate"),
            properties = buildJsonObject {
                stringProperty("fromDate", "Inclusive UTC start date.")
                stringProperty("toDate", "Inclusive UTC end date.")
                stringProperty("timezone", "IANA timezone for sleep-night classification. Defaults to UTC.")
                sourceFilterProperties()
                booleanProperty("includeSource", "Include source provider metadata when supported.")
            },
        ),
        toolAnnotations = readOnlyAnnotations("Dashboard summary"),
    ) { request ->
        tools.dashboardSummary(request.arguments.toArguments())
    }

    server.addTool(
        name = "dashboard_trends",
        description = "Return period-over-period trends for steps, heart rate, sleep, and weight.",
        inputSchema = objectSchema(
            properties = buildJsonObject {
                integerProperty("periodDays", "Number of days in the comparison period, 1 to 90.")
                stringProperty("toDate", "End date for the current period. Defaults to today.")
            },
        ),
        toolAnnotations = readOnlyAnnotations("Dashboard trends"),
    ) { request ->
        tools.dashboardTrends(request.arguments.toArguments())
    }

    server.addTool(
        name = "query_health_metric",
        description = "Query any supported read-only health metric family and mode with existing REST filters.",
        inputSchema = objectSchema(
            required = listOf("family", "mode"),
            properties = buildJsonObject {
                stringEnumProperty(
                    "family",
                    "Metric family to query.",
                    listOf(
                        "steps",
                        "activity",
                        "sleep",
                        "body_measurements",
                        "heart_rate",
                        "respiratory_rate",
                        "hrv",
                        "blood_pressure",
                        "cardiovascular",
                        "extended_body_measurements",
                    ),
                )
                stringProperty(
                    "mode",
                    "Family-specific read mode, such as samples, daily, latest, summary, sessions, nights, summaries, or measurements.",
                )
                stringProperty("from", "Inclusive ISO-8601 instant.")
                stringProperty("to", "Exclusive ISO-8601 instant.")
                stringProperty("date", "Exact date or today for date-based reads.")
                stringProperty("fromDate", "Inclusive date for date-based reads.")
                stringProperty("toDate", "Inclusive date for date-based reads.")
                stringProperty("timezone", "IANA timezone when supported.")
                stringProperty("metricType", "Family-specific metric type filter.")
                sourceFilterProperties()
                booleanProperty("includeSource", "Include source provider metadata when supported.")
                booleanProperty("latest", "Return latest matching item when supported by the selected mode.")
                integerProperty("limit", "Maximum items. Existing endpoint limits apply.")
                stringProperty("sort", "Endpoint-specific sort field.")
                stringEnumProperty("order", "Sort direction.", listOf("asc", "desc"))
            },
        ),
        toolAnnotations = readOnlyAnnotations("Query health metric"),
    ) { request ->
        tools.queryHealthMetric(request.arguments.toArguments())
    }

    return server
}

private fun readOnlyAnnotations(title: String): ToolAnnotations =
    ToolAnnotations(
        title = title,
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = false,
    )

private fun objectSchema(
    properties: JsonObject = JsonObject(emptyMap()),
    required: List<String> = emptyList(),
): ToolSchema =
    ToolSchema(
        properties = properties,
        required = required,
    )

private fun kotlinx.serialization.json.JsonObjectBuilder.stringProperty(
    name: String,
    description: String,
) {
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.stringEnumProperty(
    name: String,
    description: String,
    values: List<String>,
) {
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
        putJsonArray("enum") {
            values.forEach { add(JsonPrimitive(it)) }
        }
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.booleanProperty(
    name: String,
    description: String,
) {
    putJsonObject(name) {
        put("type", "boolean")
        put("description", description)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.integerProperty(
    name: String,
    description: String,
) {
    putJsonObject(name) {
        put("type", "integer")
        put("description", description)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.sourceFilterProperties() {
    stringProperty("provider", "Source provider filter.")
    stringProperty("providerInstanceId", "Source provider instance filter.")
}

private fun kotlinx.serialization.json.JsonArrayBuilder.addObjectSchema(type: String) {
    add(
        buildJsonObject {
            put("type", type)
        }
    )
}

private fun kotlinx.serialization.json.JsonArrayBuilder.addArraySchema(
    itemType: String,
    itemEnum: List<String>,
) {
    add(
        buildJsonObject {
            put("type", "array")
            putJsonObject("items") {
                put("type", itemType)
                putJsonArray("enum") {
                    itemEnum.forEach { add(JsonPrimitive(it)) }
                }
            }
        }
    )
}
