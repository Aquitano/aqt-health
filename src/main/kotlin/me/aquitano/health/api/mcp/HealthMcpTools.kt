package me.aquitano.health.api.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.aquitano.health.api.ApplicationServices
import me.aquitano.health.application.metric.common.QueryParams
import me.aquitano.health.domain.NotFoundException
import me.aquitano.health.domain.RequestValidationException
import me.aquitano.health.domain.ValidationIssue
import me.aquitano.health.domain.ValidationIssueCodes
import me.aquitano.health.shared.AppJson

class HealthMcpTools(
    private val services: ApplicationServices,
) {
    suspend fun catalog(): CallToolResult =
        jsonResult(services.metricCatalogService.catalog())

    suspend fun healthDay(arguments: JsonObject): CallToolResult =
        toolResult {
            services.healthDayQueryService.getHealthDay(
                arguments.toQueryParams(
                    moduleFields = setOf("modules"),
                ),
                services.clock.now(),
            )
        }

    suspend fun dashboardSummary(arguments: JsonObject): CallToolResult =
        toolResult {
            services.metricsQueryService.dashboardSummary(
                arguments.toQueryParams(),
                services.clock.now(),
            )
        }

    suspend fun dashboardTrends(arguments: JsonObject): CallToolResult =
        toolResult {
            services.trendQueryService.dashboardTrends(
                arguments.toQueryParams(),
                services.clock.now(),
            )
        }

    suspend fun queryHealthMetric(arguments: JsonObject): CallToolResult =
        toolResult {
            val family = arguments.requiredString("family")
            val mode = arguments.requiredString("mode")
            val params = arguments.toQueryParams(excludedFields = setOf("family", "mode"))

            when (family to mode) {
                "steps" to "samples" -> services.metricsQueryService.listStepSamples(params)
                "steps" to "daily" -> services.metricsQueryService.listStepDailySummaries(params, services.clock.now())
                "activity" to "daily" -> services.metricsQueryService.listActivitySummaries(params, services.clock.now())
                "activity" to "latest" -> services.metricsQueryService.latestActivitySummary(params, services.clock.now())
                "sleep" to "sessions" -> services.metricsQueryService.listSleepSessions(params)
                "sleep" to "nights" -> services.metricsQueryService.listSleepNights(params, services.clock.now())
                "sleep" to "summaries" -> services.sleepSummaryReadService.list(params)
                "sleep" to "summaryLatest" -> services.sleepSummaryReadService.latest(params)
                "body_measurements" to "measurements" -> services.metricsQueryService.listBodyMeasurements(params)
                "body_measurements" to "latest" -> services.metricsQueryService.latestBodyMeasurement(params)
                "heart_rate" to "samples" -> services.metricsQueryService.listHeartRateSamples(params)
                "heart_rate" to "summary" -> services.metricsQueryService.heartRateSummary(params)
                "respiratory_rate" to "samples" -> services.metricsQueryService.listRespiratoryRateSamples(params)
                "respiratory_rate" to "summary" -> services.metricsQueryService.respiratoryRateSummary(params)
                "hrv" to "samples" -> services.metricsQueryService.listHrvSamples(params)
                "hrv" to "summary" -> services.metricsQueryService.hrvSummary(params)
                "blood_pressure" to "measurements" -> services.metricsQueryService.listBloodPressure(params)
                "blood_pressure" to "latest" -> services.metricsQueryService.latestBloodPressure(params)
                "cardiovascular" to "measurements" -> services.metricsQueryService.listCardiovascular(params)
                "cardiovascular" to "latest" -> services.metricsQueryService.latestCardiovascular(params)
                "extended_body_measurements" to "measurements" ->
                    services.metricsQueryService.listExtendedBodyMeasurements(params)
                "extended_body_measurements" to "latest" ->
                    services.metricsQueryService.latestExtendedBodyMeasurement(params)
                else -> throw RequestValidationException(
                    listOf(
                        ValidationIssue(
                            field = "mode",
                            code = ValidationIssueCodes.UnsupportedValue,
                            message = "unsupported family/mode combination $family/$mode",
                        )
                    )
                )
            }
        }

    private suspend inline fun <reified T> toolResult(
        crossinline block: suspend () -> T,
    ): CallToolResult =
        try {
            jsonResult(block())
        } catch (error: RequestValidationException) {
            validationErrorResult(error)
        } catch (error: NotFoundException) {
            errorResult(
                McpToolError(
                    code = "not_found",
                    message = error.message ?: "Resource not found",
                )
            )
        }

    private inline fun <reified T> jsonResult(value: T): CallToolResult =
        CallToolResult(content = listOf(TextContent(AppJson.encodeToString(value))))

    private fun validationErrorResult(error: RequestValidationException): CallToolResult =
        errorResult(
            McpToolError(
                code = "validation_failed",
                message = "Request validation failed",
                details = error.issues.map {
                    McpToolErrorDetail(
                        field = it.field,
                        code = it.code,
                        message = it.message,
                    )
                },
            )
        )

    private fun errorResult(value: McpToolError): CallToolResult =
        CallToolResult(
            content = listOf(TextContent(AppJson.encodeToString(value))),
            isError = true,
        )
}

@Serializable
private data class McpToolError(
    val code: String,
    val message: String,
    val details: List<McpToolErrorDetail> = emptyList(),
)

@Serializable
private data class McpToolErrorDetail(
    val field: String,
    val code: String,
    val message: String,
)

internal fun JsonObject.toQueryParams(
    excludedFields: Set<String> = emptySet(),
    moduleFields: Set<String> = emptySet(),
): QueryParams =
    QueryParams(
        entries
            .asSequence()
            .filterNot { it.key in excludedFields }
            .mapNotNull { (key, value) ->
                value.toQueryParamValue(key in moduleFields)?.let { key to it }
            }
            .toMap()
    )

internal fun JsonElement?.toArguments(): JsonObject =
    when (this) {
        null, JsonNull -> JsonObject(emptyMap())
        is JsonObject -> this
        else -> throw RequestValidationException(
            listOf(
                ValidationIssue(
                    field = "arguments",
                    code = ValidationIssueCodes.InvalidFormat,
                    message = "must be a JSON object",
                )
            )
        )
    }

private fun JsonElement.toQueryParamValue(joinArray: Boolean): String? =
    when (this) {
        JsonNull -> null
        is JsonPrimitive -> scalarValue()
        is JsonArray -> {
            if (!joinArray) {
                throw RequestValidationException(
                    listOf(
                        ValidationIssue(
                            field = "arguments",
                            code = ValidationIssueCodes.InvalidFormat,
                            message = "arrays are only supported for module arguments",
                        )
                    )
                )
            }
            jsonArray.joinToString(",") { it.jsonPrimitive.content }
        }
        else -> throw RequestValidationException(
            listOf(
                ValidationIssue(
                    field = "arguments",
                    code = ValidationIssueCodes.InvalidFormat,
                    message = "nested objects are not supported as query arguments",
                )
            )
        )
    }

private fun JsonPrimitive.scalarValue(): String? =
    contentOrNull
        ?: booleanOrNull?.toString()
        ?: intOrNull?.toString()
        ?: doubleOrNull?.toString()

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: throw RequestValidationException(
            listOf(
                ValidationIssue(
                    field = name,
                    code = ValidationIssueCodes.Required,
                    message = "is required",
                )
            )
        )
