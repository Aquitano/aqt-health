package me.aquitano.health.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.authorized
import me.aquitano.health.test.configureTestApplication
import me.aquitano.health.test.jsonBody
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** Reads at the edges that only show up with real calendars: DST days, local midnight, tied timestamps. */
class ReadBoundaryRouteTest : PostgresIntegrationTest() {
    @Test
    fun localDayStepTotalsFollowDaylightSavingDayLengths() =
        testApplication {
            configureTestApplication()
            // 100 steps in every UTC hour around both 2026 Berlin transitions
            ingest(
                hourlySteps("2026-03-28T00:00:00Z", "2026-03-31T00:00:00Z") +
                    hourlySteps("2026-10-24T00:00:00Z", "2026-10-27T00:00:00Z"),
            )

            for ((date, steps, buckets) in listOf(
                Triple("2026-03-29", 2300, 92),
                Triple("2026-03-30", 2400, 96),
                Triple("2026-10-25", 2500, 100),
            )) {
                val daily = authorizedGet("/api/v2/steps/daily?date=$date&timezone=Europe/Berlin").items()
                assertEquals(
                    steps,
                    daily
                        .single()
                        .jsonObject["steps"]!!
                        .jsonPrimitive.int,
                    date,
                )

                val day = authorizedGet("/api/v2/health/day?date=$date&timezone=Europe/Berlin&modules=steps").jsonBody()["steps"]!!.jsonObject
                assertEquals(steps, day["total"]!!.jsonPrimitive.int, date)
                assertEquals(buckets, day["buckets"]!!.jsonArray.size, date)
            }
        }

    @Test
    fun stepIntervalAcrossLocalMidnightSplitsByLocalDay() =
        testApplication {
            configureTestApplication()
            // 23:50 to 00:10 in Berlin
            ingest(listOf(stepInterval("midnight", "2026-05-10T21:50:00Z", "2026-05-10T22:10:00Z", 120)))

            fun totals(json: JsonArray) = json.associate { it.jsonObject["date"]!!.jsonPrimitive.content to it.jsonObject["steps"]!!.jsonPrimitive.int }

            for (zone in listOf("Europe/Berlin", "%2B02:00")) {
                assertEquals(
                    mapOf("2026-05-10" to 60, "2026-05-11" to 60),
                    totals(authorizedGet("/api/v2/steps/daily?fromDate=2026-05-10&toDate=2026-05-11&timezone=$zone").items()),
                    zone,
                )
            }
            assertEquals(
                mapOf("2026-05-10" to 120),
                totals(authorizedGet("/api/v2/steps/daily?fromDate=2026-05-10&toDate=2026-05-11&timezone=UTC").items()).filterValues { it > 0 },
            )
        }

    @Test
    fun cursorPagesThroughRowsWithIdenticalTimestamps() =
        testApplication {
            configureTestApplication()
            val contexts = listOf("active", "general", "resting", "sleep", "standing", "unknown", "workout")
            ingest(
                contexts.mapIndexed { index, context ->
                    """{"type":"scalar","providerRecordId":"tie-$context","measuredAt":"2026-05-29T12:00:00Z","metricType":"heart_rate","value":${60 + index},"context":"$context"}"""
                },
            )

            val path = "/api/v2/metrics/heart_rate?from=2026-05-29T12:00:00Z&to=2026-05-29T12:00:01Z&limit=3"
            val ids = mutableListOf<Int>()
            var cursor: String? = null
            do {
                val page = authorizedGet(path + (cursor?.let { "&cursor=$it" } ?: "")).jsonBody()
                ids += page["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int }
                cursor = page["meta"]!!.jsonObject["nextCursor"]?.jsonPrimitive?.content
            } while (cursor != null)

            assertEquals(contexts.size, ids.size)
            assertEquals(contexts.size, ids.toSet().size)
        }

    private fun stepInterval(
        recordId: String,
        startAt: String,
        endAt: String,
        steps: Int,
    ) = """{"type":"step_interval","providerRecordId":"$recordId","startAt":"$startAt","endAt":"$endAt","steps":$steps}"""

    private fun hourlySteps(
        from: String,
        to: String,
    ): List<String> =
        generateSequence(Instant.parse(from)) { it.plus(Duration.ofHours(1)) }
            .takeWhile { it < Instant.parse(to) }
            .map { stepInterval("hour-$it", it.toString(), it.plus(Duration.ofHours(1)).toString(), 100) }
            .toList()

    private suspend fun ApplicationTestBuilder.ingest(records: List<String>) {
        val response =
            client.post("/api/v2/ingestion/batches") {
                authorized()
                contentType(ContentType.Application.Json)
                setBody("""{"provider":"health_connect","providerInstanceId":"phone","ingestedAt":"2026-11-01T00:00:00Z","sourcePayload":{},"records":[${records.joinToString(",")}]}""")
            }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.authorizedGet(path: String) = client.get(path) { authorized() }

    private suspend fun HttpResponse.items(): JsonArray = jsonBody()["items"]!!.jsonArray
}
