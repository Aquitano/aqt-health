@file:OptIn(ExperimentalKtorApi::class)

package me.aquitano.health.api

import io.ktor.http.*
import io.ktor.openapi.*
import io.ktor.server.routing.*
import io.ktor.server.routing.openapi.*
import io.ktor.utils.io.*
import me.aquitano.health.api.dto.*
import kotlin.reflect.typeOf

internal fun Operation.Builder.publicEndpoint() {
    security {
        optional()
    }
}

internal inline fun <reified T : Any> Operation.Builder.jsonRequest(
    descriptionText: String,
    namedExample: Pair<String, ExampleObject>? = null,
) {
    requestBody {
        description = descriptionText
        required = true
        content {
            schema = buildSchema(typeOf<T>())
            namedExample?.let { (name, value) -> example(name, value) }
        }
    }
}

internal fun Operation.Builder.errorResponses(
    unauthorized: Boolean = true,
    validation: Boolean = true,
    notFound: Boolean = false,
    conflict: Boolean = false,
    upstream: Boolean = false,
) {
    responses {
        commonErrors(
            unauthorized = unauthorized,
            validation = validation,
            notFound = notFound,
            conflict = conflict,
            upstream = upstream,
        )
        defaultError()
    }
}

internal fun Responses.Builder.defaultError() {
    default {
        description = "Error response"
        content {
            schema = buildSchema(typeOf<ErrorResponse>())
            example(
                "error",
                internalErrorExample(),
            )
        }
    }
}

internal fun Responses.Builder.commonErrors(
    unauthorized: Boolean = true,
    validation: Boolean = true,
    notFound: Boolean = false,
    conflict: Boolean = false,
    payloadTooLarge: Boolean = false,
    upstream: Boolean = false,
) {
    if (validation) {
        HttpStatusCode.BadRequest {
            description = "Request validation failed"
            content {
                schema = buildSchema(typeOf<ErrorResponse>())
                example("validation", validationErrorExample())
            }
        }
    }
    if (unauthorized) {
        HttpStatusCode.Unauthorized {
            description = "Missing or invalid API key"
            content {
                schema = buildSchema(typeOf<ErrorResponse>())
                example("unauthorized", unauthorizedErrorExample())
            }
        }
    }
    if (notFound) {
        HttpStatusCode.NotFound {
            description = "Resource not found"
            content {
                schema = buildSchema(typeOf<ErrorResponse>())
                example("notFound", notFoundErrorExample())
            }
        }
    }
    if (conflict) {
        HttpStatusCode.Conflict {
            description = "Request conflicts with current state"
            content {
                schema = buildSchema(typeOf<ErrorResponse>())
                example("conflict", conflictErrorExample())
            }
        }
    }
    if (payloadTooLarge) {
        HttpStatusCode.PayloadTooLarge {
            description = "Request body exceeds the configured size limit"
            content {
                schema = buildSchema(typeOf<ErrorResponse>())
                example("payloadTooLarge", payloadTooLargeErrorExample())
            }
        }
    }
    if (upstream) {
        HttpStatusCode.BadGateway {
            description = "Upstream provider request failed"
            content {
                schema = buildSchema(typeOf<ErrorResponse>())
                example("upstream", upstreamErrorExample())
            }
        }
    }
    HttpStatusCode.InternalServerError {
        description = "Unexpected server error"
        content {
            schema = buildSchema(typeOf<ErrorResponse>())
            example("internal", internalErrorExample())
        }
    }
}

internal fun Route.describeReadOperation(
    operationId: String,
    summary: String,
    descriptionText: String,
): Route =
    describe {
        this.operationId = operationId
        tag("Read")
        this.summary = summary
        description = descriptionText
        readQueryParameters()
        errorResponses()
    }

internal fun Route.describeDailyReadOperation(
    id: String,
    operationSummary: String,
    operationDescription: String,
    localDays: Boolean = false,
    latestDescription: String? = null,
): Route =
    describe {
        operationId = id
        tag("Read")
        summary = operationSummary
        description = operationDescription
        dailyQueryParameters(localDays)
        latestDescription?.let {
            parameters {
                query("latest") {
                    description = it
                    schema = booleanSchema(default = false, example = true)
                }
            }
        }
        errorResponses()
    }
