package me.aquitano.health.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
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
        mdc("requestId") { call -> call.callId.orEmpty() }
        mdc("method") { call -> call.request.httpMethod.value }
        mdc("path") { call -> call.request.path() }
        mdc("clientIp") { call -> call.request.local.remoteHost }
        mdc("userAgent") { call -> call.request.headers[HttpHeaders.UserAgent].orEmpty() }
        mdc("status") { call ->
            call.response
                .status()
                ?.value
                ?.toString()
                .orEmpty()
        }
        mdc("durationMs") { call -> call.processingTimeMillis().toString() }
        mdc("clientId") { call ->
            call.attributes
                .getOrNull(ApiClientAttributeKey)
                ?.id
                ?.toString()
                .orEmpty()
        }
        mdc("clientName") { call ->
            call.attributes
                .getOrNull(ApiClientAttributeKey)
                ?.name
                .orEmpty()
        }
    }
}
