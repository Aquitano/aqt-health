package me.aquitano.health.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.application.hooks.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import me.aquitano.health.infrastructure.repositories.ApiClientRef
import org.slf4j.MDC
import org.slf4j.event.Level
import java.util.*

fun Application.configureRequestLogging() {
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() }
        replyToHeader(HttpHeaders.XRequestId)
    }

    install(CallLogging) {
        level = Level.INFO
        // CallLogging computes each MDC value once, when the call starts, so its own completion
        // line would carry no status, a near-zero duration and no client. RequestCompletionLogging
        // writes that line instead; these entries tag every log line written during the call.
        filter { false }
        mdc("requestId") { call -> call.callId.orEmpty() }
        mdc("method") { call -> call.request.httpMethod.value }
        mdc("path") { call -> call.request.path() }
        mdc("clientIp") { call -> call.request.local.remoteHost }
        mdc("userAgent") { call -> call.request.headers[HttpHeaders.UserAgent].orEmpty() }
    }
    install(RequestCompletionLogging)
}

private val RequestCompletionLogging =
    createApplicationPlugin("RequestCompletionLogging") {
        on(ResponseSent) { call ->
            val status = call.response.status()
            val durationMs = call.processingTimeMillis()
            val client = call.principal<ApiClientRef>()
            val fields =
                mapOf(
                    "status" to status?.value?.toString().orEmpty(),
                    "durationMs" to durationMs.toString(),
                    "clientId" to client?.id?.toString().orEmpty(),
                    "clientName" to client?.name.orEmpty(),
                )
            fields.forEach(MDC::put)
            try {
                application.log.info("{}: {} - {} in {}ms", status, call.request.httpMethod.value, call.request.path(), durationMs)
            } finally {
                fields.keys.forEach(MDC::remove)
            }
        }
    }
