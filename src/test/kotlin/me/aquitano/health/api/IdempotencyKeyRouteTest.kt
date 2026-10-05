package me.aquitano.health.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.authorized
import me.aquitano.health.test.configureTestApplication
import me.aquitano.health.test.googleHealthTestConfig
import me.aquitano.health.test.jsonBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class IdempotencyKeyRouteTest : PostgresIntegrationTest() {
    @Test
    fun syncJobSameKeyReturnsSameJob() =
        testApplication {
            configureTestApplication(*googleHealthTestConfig())

            val first = startSyncJob(key = "sync-job-key-1")
            val second = startSyncJob(key = "sync-job-key-1")

            assertEquals(HttpStatusCode.Accepted, first.status)
            assertEquals(HttpStatusCode.Accepted, second.status)
            assertEquals(first.jobId(), second.jobId())
        }

    @Test
    fun syncJobDifferentKeysCreateDistinctJobs() =
        testApplication {
            configureTestApplication(*googleHealthTestConfig())

            val first = startSyncJob(key = "sync-job-key-a")
            val second = startSyncJob(key = "sync-job-key-b")

            assertNotEquals(first.jobId(), second.jobId())
        }

    @Test
    fun syncJobSameKeyWithDifferentRequestReturnsConflict() =
        testApplication {
            configureTestApplication(*googleHealthTestConfig())

            val first = startSyncJob(key = "sync-job-key-conflict")
            val second =
                client.post("/api/v2/providers/google-health/sync-jobs") {
                    authorized()
                    header("Idempotency-Key", "sync-job-key-conflict")
                    contentType(ContentType.Application.Json)
                    setBody("""{"from":"2026-04-02T00:00:00Z","to":"2026-04-03T00:00:00Z","dataTypes":["steps"]}""")
                }

            assertEquals(HttpStatusCode.Accepted, first.status)
            assertEquals(HttpStatusCode.Conflict, second.status)
            assertEquals("idempotency_key_conflict", second.errorCode())
        }

    @Test
    fun syncJobWithoutKeyCreatesNewJobEachTime() =
        testApplication {
            configureTestApplication(*googleHealthTestConfig())

            val first = startSyncJob(key = null)
            val second = startSyncJob(key = null)

            assertEquals(HttpStatusCode.Accepted, first.status)
            assertEquals(HttpStatusCode.Accepted, second.status)
            assertNotEquals(first.jobId(), second.jobId())
        }

    @Test
    fun replaySameKeyReturnsSameJob() =
        testApplication {
            configureTestApplication(*googleHealthTestConfig())

            val first =
                client.post("/api/v2/admin/replay") {
                    authorized()
                    header("Idempotency-Key", "replay-key-1")
                    contentType(ContentType.Application.Json)
                    setBody("""{"scope":"projections"}""")
                }
            val second =
                client.post("/api/v2/admin/replay") {
                    authorized()
                    header("Idempotency-Key", "replay-key-1")
                    contentType(ContentType.Application.Json)
                    setBody("""{"scope":"projections"}""")
                }

            assertEquals(HttpStatusCode.Accepted, first.status)
            assertEquals(HttpStatusCode.Accepted, second.status)
            assertEquals(first.jobId(), second.jobId())
        }

    @Test
    fun replaySameKeyWithDifferentRequestReturnsConflict() =
        testApplication {
            configureTestApplication(*googleHealthTestConfig())

            val first =
                client.post("/api/v2/admin/replay") {
                    authorized()
                    header("Idempotency-Key", "replay-key-conflict")
                    contentType(ContentType.Application.Json)
                    setBody("""{"scope":"projections"}""")
                }
            val second =
                client.post("/api/v2/admin/replay") {
                    authorized()
                    header("Idempotency-Key", "replay-key-conflict")
                    contentType(ContentType.Application.Json)
                    setBody("""{"scope":"derived"}""")
                }

            assertEquals(HttpStatusCode.Accepted, first.status)
            assertEquals(HttpStatusCode.Conflict, second.status)
            assertEquals("idempotency_key_conflict", second.errorCode())
        }

    private suspend fun ApplicationTestBuilder.startSyncJob(key: String?): HttpResponse =
        client.post("/api/v2/providers/google-health/sync-jobs") {
            authorized()
            if (key != null) {
                header("Idempotency-Key", key)
            }
            contentType(ContentType.Application.Json)
            setBody("""{"from":"2026-04-01T00:00:00Z","to":"2026-04-02T00:00:00Z","dataTypes":["steps"]}""")
        }

    private suspend fun HttpResponse.jobId(): String = jsonBody()["jobId"]!!.jsonPrimitive.content

    private suspend fun HttpResponse.errorCode(): String = jsonBody()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
}
