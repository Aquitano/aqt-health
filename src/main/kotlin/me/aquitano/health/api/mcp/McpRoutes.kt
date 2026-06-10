package me.aquitano.health.api.mcp

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.sse
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import me.aquitano.health.api.ApplicationServices
import me.aquitano.health.api.requireApiClient
import java.util.concurrent.ConcurrentHashMap

private const val MCP_SESSION_ID_HEADER = "mcp-session-id"

fun Application.configureMcpRoutes(services: ApplicationServices) {
    val transports = ConcurrentHashMap<String, StreamableHttpServerTransport>()

    routing {
        route("/mcp") {
            sse {
                call.authenticateMcp(services)
                val transport = findTransport(call, transports) ?: return@sse
                transport.handleRequest(this, call)
            }
            get {
                call.authenticateMcp(services)
                val transport = findTransport(call, transports) ?: return@get
                transport.handleRequest(null, call)
            }
            post {
                call.authenticateMcp(services)
                val transport = getOrCreateTransport(call, transports, services) ?: return@post
                transport.handleRequest(null, call)
            }
            delete {
                call.authenticateMcp(services)
                val transport = findTransport(call, transports) ?: return@delete
                transport.handleRequest(null, call)
            }
        }
    }
}

private suspend fun ApplicationCall.authenticateMcp(services: ApplicationServices) {
    requireApiClient(
        supportRepository = services.supportRepository,
        apiKeyHasher = services.apiKeyHasher,
        clock = services.clock,
    )
}

private suspend fun findTransport(
    call: ApplicationCall,
    transports: ConcurrentHashMap<String, StreamableHttpServerTransport>,
): StreamableHttpServerTransport? {
    val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
    if (sessionId.isNullOrBlank()) {
        call.respond(HttpStatusCode.BadRequest, "Bad Request: No valid session ID provided")
        return null
    }

    val transport = transports[sessionId]
    if (transport == null) {
        call.respond(HttpStatusCode.NotFound, "Session not found")
    }
    return transport
}

private suspend fun getOrCreateTransport(
    call: ApplicationCall,
    transports: ConcurrentHashMap<String, StreamableHttpServerTransport>,
    services: ApplicationServices,
): StreamableHttpServerTransport? {
    val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
    if (sessionId != null) {
        val transport = transports[sessionId]
        if (transport == null) {
            call.respond(HttpStatusCode.NotFound, "Session not found")
        }
        return transport
    }

    val transport = StreamableHttpServerTransport(
        StreamableHttpServerTransport.Configuration(
            enableJsonResponse = true,
        )
    )
    transport.setOnSessionInitialized { initializedSessionId ->
        transports[initializedSessionId] = transport
    }
    transport.setOnSessionClosed { closedSessionId ->
        transports.remove(closedSessionId)
    }

    val server = createHealthMcpServer(services)
    server.onClose {
        transport.sessionId?.let { transports.remove(it) }
    }
    server.createSession(transport)
    return transport
}
