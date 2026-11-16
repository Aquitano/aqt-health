package me.aquitano.health.api

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
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
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            testApplication {
                configureTestApplication()
                client.get("/api/v2/metrics") {
                    authorized()
                    header(HttpHeaders.XRequestId, "log-test-1")
                }
            }
        } finally {
            root.detachAppender(appender)
        }

        val completion = appender.list.single { it.formattedMessage.endsWith("ms") && "GET - /api/v2/metrics" in it.formattedMessage }
        val mdc = completion.mdcPropertyMap
        assertEquals("200", mdc["status"])
        assertEquals("log-test-1", mdc["requestId"])
        assertTrue(mdc["clientName"].orEmpty().isNotEmpty())
        assertEquals(completion.formattedMessage.substringAfterLast(" in ").removeSuffix("ms"), mdc["durationMs"])
    }
}
