@file:OptIn(ExperimentalKtorApi::class)

package me.aquitano.health.api

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import io.ktor.http.*
import io.ktor.openapi.JsonSchema
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.serialization.Serializable
import me.aquitano.health.domain.*
import me.aquitano.health.domain.NotFoundException
import me.aquitano.health.infrastructure.logging.*

private val logger = KotlinLogging.logger("me.aquitano.health.api.Errors")

fun Application.configureErrorHandling() {
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondError(status, "not_found", "Resource not found", logEvent = "request_not_found")
        }
        status(HttpStatusCode.Unauthorized) { call, status ->
            call.respondError(status, "unauthorized", "Missing or invalid API key", logEvent = "request_unauthorized")
        }
        exception<RequestValidationException> { call, cause ->
            call.respondError(
                HttpStatusCode.BadRequest,
                "validation_failed",
                "Request validation failed",
                logEvent = "request_validation_failed",
                logFields = mapOf("fields" to cause.issues.map { it.field }),
                details = cause.issues.map { ErrorDetail(field = it.field, code = it.code, message = it.message) },
            )
        }
        exception<NotFoundException> { call, cause ->
            call.respondError(HttpStatusCode.NotFound, "not_found", cause.message ?: "Resource not found", logEvent = "request_not_found")
        }
        exception<ConflictException> { call, cause ->
            call.respondError(
                HttpStatusCode.Conflict,
                cause.code,
                cause.message ?: "Request conflicts with current state",
                logEvent = "request_conflict",
                logLevel = Level.WARN,
            )
        }
        exception<UpstreamProviderException> { call, cause ->
            call.respondError(
                HttpStatusCode.fromValue(cause.statusCode),
                cause.code,
                cause.message ?: "Provider request failed",
                logEvent = "upstream_provider_failed",
                logLevel = Level.WARN,
                logFields = mapOf("status" to cause.statusCode),
                throwable = cause.cause ?: cause,
            )
        }
        exception<PayloadTooLargeException> { call, _ ->
            call.respondError(
                HttpStatusCode.PayloadTooLarge,
                "payload_too_large",
                "Request body exceeds the configured size limit",
                logEvent = "request_payload_too_large",
            )
        }
        exception<BadRequestException> { call, _ ->
            call.respondError(HttpStatusCode.BadRequest, "validation_failed", "Request validation failed", logEvent = "request_bad_request")
        }
        exception<ServerConfigurationException> { call, cause ->
            call.respondError(
                HttpStatusCode.InternalServerError,
                cause.code,
                cause.publicMessage,
                logEvent = "server_configuration_error",
                logLevel = Level.ERROR,
                logFields = mapOf("fields" to cause.details.map { it.field }),
                throwable = cause,
            )
        }
        exception<Throwable> { call, cause ->
            call.respondError(
                HttpStatusCode.InternalServerError,
                "internal_error",
                "Unexpected server error",
                logEvent = "request_unexpected_error",
                logLevel = Level.ERROR,
                throwable = cause,
            )
        }
    }
}

private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
    logEvent: String,
    logLevel: Level = Level.INFO,
    logFields: Map<String, Any?> = emptyMap(),
    throwable: Throwable? = null,
    details: List<ErrorDetail>? = null,
) {
    val requestId = requestId()
    val context = mapOf("errorCode" to code) + logFields + ("requestId" to requestId)
    when (logLevel) {
        Level.ERROR -> logger.errorWithContext(logEvent, context, throwable)
        Level.WARN -> logger.warnWithContext(logEvent, context, throwable)
        else -> logger.infoWithContext(logEvent, context)
    }
    respond(status, ErrorResponse(ErrorBody(code = code, message = message, requestId = requestId, details = details)))
}

@Serializable
data class ErrorResponse(
    val error: ErrorBody,
)

@Serializable
data class ErrorBody(
    @JsonSchema.Description(
        "Stable machine-readable error code. Envelope-level values are `validation_failed`, `unauthorized`, " +
            "`not_found`, and `internal_error`; provider-sync and ingestion endpoints additionally return " +
            "provider-specific conflict and upstream codes (for example `idempotency_key_conflict`, " +
            "`scheduled_sync_already_running`, or `withings_needs_reauth`).",
    )
    val code: String,
    val message: String,
    val requestId: String,
    val details: List<ErrorDetail>? = null,
)

@Serializable
data class ErrorDetail(
    val field: String,
    @JsonSchema.Description("Machine-readable validation issue code for this field.")
    @JsonSchema.Enum(
        ValidationIssueCodes.Required,
        ValidationIssueCodes.InvalidFormat,
        ValidationIssueCodes.UnsupportedValue,
        ValidationIssueCodes.OutOfRange,
        ValidationIssueCodes.InvalidRange,
        ValidationIssueCodes.InvalidState,
    )
    val code: String,
    val message: String,
)

private fun ApplicationCall.requestId(): String = callId ?: response.headers[HttpHeaders.XRequestId] ?: "unknown"
