package me.aquitano.health.api

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.delay
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.authorized
import me.aquitano.health.test.configureTestApplication
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestLoggingTest : PostgresIntegrationTest() {
    @Test
    fun completionLineCarriesTheFinalStatusDurationAndClient() {
        val events =
            captureLogs {
                testApplication {
                    configureTestApplication()
                    routing {
                        get("/slow") {
                            delay(SLOW_MS)
                            call.respondText("done")
                        }
                    }
                    client.get("/api/v2/metrics") {
                        authorized()
                        header(HttpHeaders.XRequestId, "log-test-1")
                    }
                    client.get("/slow")
                }
            }

        val metrics = events.completionFor("/api/v2/metrics").mdcPropertyMap
        assertEquals("200", metrics["status"])
        assertEquals("log-test-1", metrics["requestId"])
        assertTrue(metrics["clientName"].orEmpty().isNotEmpty())

        val slow = events.completionFor("/slow").mdcPropertyMap
        assertTrue(slow.getValue("durationMs").toLong() >= SLOW_MS, "durationMs=${slow["durationMs"]}")
    }

    private fun captureLogs(block: () -> Unit): List<ILoggingEvent> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender.list
    }

    private fun List<ILoggingEvent>.completionFor(path: String) = single { it.mdcPropertyMap["path"] == path && it.formattedMessage.endsWith("ms") }

    private companion object {
        const val SLOW_MS = 300L
    }
}
