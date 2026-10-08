package me.aquitano.health.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.aquitano.health.infrastructure.config.DatabaseConfig
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.authorized
import me.aquitano.health.test.configureTestApplication
import me.aquitano.health.test.countRows
import me.aquitano.health.test.errorCode
import me.aquitano.health.test.execute
import me.aquitano.health.test.jsonBody
import me.aquitano.health.test.queryInt
import me.aquitano.health.test.queryString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IngestionRouteTest : PostgresIntegrationTest() {
    @Test
    fun ingestionRejectsBodyLargerThanConfiguredLimit() =
        testApplication {
            val database = configureTestApplication("aqtHealth.ingestion.maxBodyBytes" to "512")

            val response =
                client.post("/api/v2/ingestion/batches") {
                    authorized()
                    contentType(ContentType.Application.Json)
                    setBody(mixedPayload(batchExternalId = "oversized-1"))
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals("payload_too_large", response.errorCode())
            assertEquals(0, database.countRows("ingestion_batches"))
        }

    @Test
    fun ingestionRejectsInvalidRequest() =
        testApplication {
            configureTestApplication()

            val response =
                client.post("/api/v2/ingestion/batches") {
                    authorized()
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "provider": "",
                          "providerInstanceId": "pixel",
                          "ingestedAt": "2026-04-19T10:00:00Z",
                          "sourcePayload": {},
                          "records": []
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("validation_failed", response.errorCode())
        }

    @Test
    fun mixedBatchPersistsIngestionAndMetricRecords() =
        testApplication {
            val database = configureTestApplication()

            val response =
                client.post("/api/v2/ingestion/batches") {
                    authorized()
                    contentType(ContentType.Application.Json)
                    setBody(mixedPayload(batchExternalId = "mixed-1"))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = response.jsonBody()
            assertEquals(9, body["recordsReceived"]!!.jsonPrimitive.int)
            assertEquals(9, body["ingestionRecordsStored"]!!.jsonPrimitive.int)
            assertEquals(
                1,
                body["metricsCreated"]!!.jsonObject["step_samples"]!!.jsonPrimitive.int,
            )
            assertEquals(
                1,
                body["metricsCreated"]!!.jsonObject["sleep_sessions"]!!.jsonPrimitive.int,
            )
            assertEquals(
                2,
                body["metricsCreated"]!!.jsonObject["sleep_stages"]!!.jsonPrimitive.int,
            )
            assertEquals(
                1,
                body["metricsCreated"]!!.jsonObject["weight"]!!.jsonPrimitive.int,
            )
            assertEquals(
                1,
                body["metricsCreated"]!!.jsonObject["heart_rate"]!!.jsonPrimitive.int,
            )
            assertEquals(
                1,
                body["metricsCreated"]!!.jsonObject["sleep_summaries"]!!.jsonPrimitive.int,
            )

            assertEquals(1, database.countRows("ingestion_batches"))
            assertEquals(9, database.countRows("ingestion_records"))
            assertEquals(1, database.countRows("step_samples"))
            assertEquals(1, database.countRows("canonical_step_samples"))
            assertEquals(4, database.countRows("canonical_step_day_bucket_contributions"))
            assertEquals(
                1200,
                database.queryInt("SELECT SUM(value)::int FROM canonical_step_day_bucket_contributions"),
            )
            assertEquals(1, database.countRows("sleep_sessions"))
            assertEquals(2, database.countRows("sleep_stages"))
            assertEquals(
                5,
                database.queryInt(
                    "SELECT COUNT(*) FROM scalar_samples WHERE metric_type IN " +
                        "('weight', 'body_fat', 'muscle', 'water', 'visceral_fat')",
                ),
            )
            assertEquals(
                1,
                database.queryInt("SELECT COUNT(*) FROM scalar_samples WHERE metric_type = 'heart_rate'"),
            )
            assertEquals(1, database.countRows("sleep_summaries"))
            assertEquals(1, database.countRows("canonical_sleep_summaries"))
            assertEquals(
                "processed",
                database.queryString("SELECT status FROM ingestion_batches"),
            )
            assertEquals(
                "unknown",
                database.queryString("SELECT context FROM scalar_samples WHERE metric_type = 'heart_rate'"),
            )
        }

    @Test
    fun batchExternalIdIsIdempotentPerSourceInstance() =
        testApplication {
            val database = configureTestApplication()

            val first =
                client.post("/api/v2/ingestion/batches") {
                    authorized()
                    contentType(ContentType.Application.Json)
                    setBody(minimalStepPayload(batchExternalId = "dupe-batch"))
                }
            val second =
                client.post("/api/v2/ingestion/batches") {
                    authorized()
                    contentType(ContentType.Application.Json)
                    setBody(minimalStepPayload(batchExternalId = "dupe-batch"))
                }

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.OK, second.status)
            assertTrue(second.jsonBody()["duplicateBatch"]!!.jsonPrimitive.boolean)
            assertEquals(1, database.countRows("ingestion_batches"))
            assertEquals(1, database.countRows("ingestion_records"))
            assertEquals(1, database.countRows("step_samples"))
            assertEquals(
                1200,
                database.queryInt("SELECT SUM(value)::int FROM canonical_step_day_bucket_contributions"),
            )
        }

    @Test
    fun failedBatchExternalIdCanBeRetried() =
        testApplication {
            val database = configureTestApplication()
            client.get("/api/v2/admin/health")
            database.insertFailedBatch(
                provider = "health_connect",
                providerInstanceId = "pixel-8-health-connect",
                batchExternalId = "retry-batch",
            )

            val response =
                client.post("/api/v2/ingestion/batches") {
                    authorized()
                    contentType(ContentType.Application.Json)
                    setBody(minimalStepPayload(batchExternalId = "retry-batch"))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(2, database.countRows("ingestion_batches"))
            assertEquals(
                1,
                database.queryInt(
                    "SELECT COUNT(*) FROM ingestion_batches WHERE status = 'processed' AND batch_external_id = 'retry-batch'",
                ),
            )
            assertEquals(
                1,
                database.queryInt(
                    "SELECT COUNT(*) FROM ingestion_batches WHERE status = 'failed' AND batch_external_id LIKE 'retry-batch#failed:%'",
                ),
            )
            assertEquals(1, database.countRows("step_samples"))
        }

    @Test
    fun providerRecordDuplicatesDoNotInflateMetricTables() =
        testApplication {
            val database = configureTestApplication()

            repeat(2) {
                val response =
                    client.post("/api/v2/ingestion/batches") {
                        authorized()
                        contentType(ContentType.Application.Json)
                        setBody(minimalStepPayload(batchExternalId = null))
                    }
                assertEquals(HttpStatusCode.Created, response.status)
            }

            assertEquals(2, database.countRows("ingestion_batches"))
            assertEquals(2, database.countRows("ingestion_records"))
            assertEquals(1, database.countRows("step_samples"))
            assertEquals(
                1200,
                database.queryInt("SELECT SUM(value)::int FROM canonical_step_day_bucket_contributions"),
            )
        }

    @Test
    fun nonGoogleProvidersCanStoreOverlappingStepIntervals() =
        testApplication {
            val database = configureTestApplication()

            val first =
                client.post("/api/v2/ingestion/batches") {
                    authorized()
                    contentType(ContentType.Application.Json)
                    setBody(
                        stepPayload(
                            provider = "health_connect",
                            batchExternalId = "overlap-1",
                            providerRecordId = "steps-1",
                            startAt = "2026-04-19T08:00:00Z",
                            endAt = "2026-04-19T09:00:00Z",
                            steps = 1200,
                        ),
                    )
                }
            val second =
                client.post("/api/v2/ingestion/batches") {
                    authorized()
                    contentType(ContentType.Application.Json)
                    setBody(
                        stepPayload(
                            provider = "health_connect",
                            batchExternalId = "overlap-2",
                            providerRecordId = "steps-2",
                            startAt = "2026-04-19T08:30:00Z",
                            endAt = "2026-04-19T09:30:00Z",
                            steps = 800,
                        ),
                    )
                }

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.Created, second.status)
            assertEquals(2, database.countRows("step_samples"))
            assertEquals(
                2000,
                database.queryInt("SELECT SUM(value)::int FROM canonical_step_day_bucket_contributions"),
            )
        }

    /**
     * Google reports the same walk under several record ids, so its step intervals are deduplicated
     * by overlap. Overlap is half-open: an interval starting exactly where another ends is a
     * separate walk, a contained interval is the same one reported again.
     */
    @Test
    fun googleStepIntervalsAreSkippedOnlyWhenTheyActuallyOverlap() =
        testApplication {
            val database = configureTestApplication()

            val posted =
                listOf(
                    Triple("steps-base", "2026-04-19T08:00:00Z" to "2026-04-19T09:00:00Z", 1200),
                    Triple("steps-touching", "2026-04-19T09:00:00Z" to "2026-04-19T10:00:00Z", 300),
                    Triple("steps-contained", "2026-04-19T08:30:00Z" to "2026-04-19T08:45:00Z", 400),
                ).map { (recordId, range, steps) ->
                    client.post("/api/v2/ingestion/batches") {
                        authorized()
                        contentType(ContentType.Application.Json)
                        setBody(
                            stepPayload(
                                provider = "google_health",
                                batchExternalId = recordId,
                                providerRecordId = recordId,
                                startAt = range.first,
                                endAt = range.second,
                                steps = steps,
                            ),
                        )
                    }
                }

            posted.forEach { assertEquals(HttpStatusCode.Created, it.status) }
            assertEquals(
                "steps-base,steps-touching",
                database.queryString("SELECT string_agg(provider_record_id, ',' ORDER BY start_at) FROM step_samples"),
            )
        }

    @Test
    fun crossMidnightStepIntervalsAreSplitAcrossDailySummaries() =
        testApplication {
            val database = configureTestApplication()

            val response =
                client.post("/api/v2/ingestion/batches") {
                    authorized()
                    contentType(ContentType.Application.Json)
                    setBody(
                        stepPayload(
                            provider = "health_connect",
                            batchExternalId = "cross-midnight",
                            providerRecordId = "steps-cross-midnight",
                            startAt = "2026-04-19T23:00:00Z",
                            endAt = "2026-04-20T01:00:00Z",
                            steps = 120,
                        ),
                    )
                }

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(
                2,
                response.jsonBody()["affectedStepSummaryDates"]!!.jsonArray.size,
            )
            assertEquals(2, database.queryInt("SELECT COUNT(DISTINCT date) FROM canonical_step_day_bucket_contributions"))
            assertEquals(
                60,
                database.queryInt(
                    "SELECT SUM(value)::int FROM canonical_step_day_bucket_contributions WHERE date = '2026-04-19'",
                ),
            )
            assertEquals(
                60,
                database.queryInt(
                    "SELECT SUM(value)::int FROM canonical_step_day_bucket_contributions WHERE date = '2026-04-20'",
                ),
            )

            // The sample is stored under both dates, but reads must return it once.
            assertEquals(2, database.countRows("canonical_step_samples"))
            val samples =
                client.get(
                    "/api/v2/steps?from=2026-04-19T00:00:00Z&to=2026-04-21T00:00:00Z",
                ) { authorized() }
            assertEquals(HttpStatusCode.OK, samples.status)
            assertEquals(1, samples.jsonBody()["items"]!!.jsonArray.size)
        }

    @Test
    fun idLessSamplesAtTheSameInstantAreDistinctPerContext() =
        testApplication {
            val database = configureTestApplication()

            val response =
                client.post("/api/v2/ingestion/batches") {
                    authorized()
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "provider": "health_connect",
                          "providerInstanceId": "pixel-1",
                          "batchExternalId": "context-natural-key",
                          "ingestedAt": "2026-04-19T12:00:00Z",
                          "sourcePayload": {},
                          "records": [
                            {
                              "type": "scalar",
                              "measuredAt": "2026-04-19T02:00:00Z",
                              "metricType": "heart_rate",
                              "value": 58,
                              "context": "sleep"
                            },
                            {
                              "type": "scalar",
                              "measuredAt": "2026-04-19T02:00:00Z",
                              "metricType": "heart_rate",
                              "value": 72
                            }
                          ]
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(
                2,
                database.queryInt("SELECT COUNT(*) FROM scalar_samples WHERE metric_type = 'heart_rate'"),
            )
        }

    private fun DatabaseConfig.insertFailedBatch(
        provider: String,
        providerInstanceId: String,
        batchExternalId: String,
    ) {
        execute(
            """
            INSERT INTO sources (code, display_name, created_at)
            VALUES ('$provider', NULL, '2026-04-19T09:00:00Z')
            """.trimIndent(),
        )
        execute(
            """
            INSERT INTO source_instances (source_id, provider_instance_id, display_name, created_at, updated_at)
            VALUES (1, '$providerInstanceId', NULL, '2026-04-19T09:00:00Z', '2026-04-19T09:00:00Z')
            """.trimIndent(),
        )
        execute(
            """
            INSERT INTO ingestion_batches (
                source_instance_id,
                batch_external_id,
                source_payload_json,
                status,
                ingested_at,
                received_at,
                processed_at,
                error_message,
                created_at,
                updated_at
            )
            VALUES (
                1,
                '$batchExternalId',
                '{}',
                'failed',
                '2026-04-19T09:00:00Z',
                '2026-04-19T09:00:00Z',
                NULL,
                'previous failure',
                '2026-04-19T09:00:00Z',
                '2026-04-19T09:00:00Z'
            )
            """.trimIndent(),
        )
    }

    private fun minimalStepPayload(batchExternalId: String? = "steps-1-batch"): String =
        stepPayload(
            provider = "health_connect",
            batchExternalId = batchExternalId,
            providerRecordId = "steps-1",
            startAt = "2026-04-19T08:00:00Z",
            endAt = "2026-04-19T09:00:00Z",
            steps = 1200,
        )

    private fun stepPayload(
        provider: String,
        batchExternalId: String?,
        providerRecordId: String,
        startAt: String,
        endAt: String,
        steps: Int,
    ): String {
        val batch =
            batchExternalId?.let { """"batchExternalId": "$it",""" } ?: ""
        return """
            {
              "provider": "$provider",
              "providerInstanceId": "pixel-8-health-connect",
              $batch
              "ingestedAt": "2026-04-19T10:00:00Z",
                "sourcePayload": {
                "exportId": "steps"
              },
              "records": [
                {
                  "type": "step_interval",
                  "providerRecordId": "$providerRecordId",
                  "startAt": "$startAt",
                  "endAt": "$endAt",
                  "steps": $steps
                }
              ]
            }
            """.trimIndent()
    }

    private fun mixedPayload(batchExternalId: String): String =
        """
        {
          "provider": "health-connect",
          "providerInstanceId": "pixel-8-health-connect",
          "batchExternalId": "$batchExternalId",
          "ingestedAt": "2026-04-19T10:00:00Z",
          "sourcePayload": {
            "exportId": "$batchExternalId"
          },
          "records": [
            {
              "type": "step_interval",
              "providerRecordId": "steps-1",
              "startAt": "2026-04-19T08:00:00Z",
              "endAt": "2026-04-19T09:00:00Z",
              "steps": 1200
            },
            {
              "type": "sleep_session",
              "providerRecordId": "sleep-1",
              "startAt": "2026-04-18T22:30:00Z",
              "endAt": "2026-04-19T06:45:00Z",
              "stages": [
                {
                  "stage": "light",
                  "startAt": "2026-04-18T22:30:00Z",
                  "endAt": "2026-04-19T00:15:00Z"
                },
                {
                  "stage": "deep",
                  "startAt": "2026-04-19T00:15:00Z",
                  "endAt": "2026-04-19T02:00:00Z"
                }
              ]
            },
            {
              "type": "scalar",
              "providerRecordId": "body-1:weight",
              "measuredAt": "2026-04-19T07:00:00Z",
              "metricType": "weight",
              "value": 82.4
            },
            {
              "type": "scalar",
              "providerRecordId": "body-1:body_fat",
              "measuredAt": "2026-04-19T07:00:00Z",
              "metricType": "body_fat",
              "value": 18.2
            },
            {
              "type": "scalar",
              "providerRecordId": "body-1:muscle",
              "measuredAt": "2026-04-19T07:00:00Z",
              "metricType": "muscle",
              "value": 34.7
            },
            {
              "type": "scalar",
              "providerRecordId": "body-1:water",
              "measuredAt": "2026-04-19T07:00:00Z",
              "metricType": "water",
              "value": 55.1
            },
            {
              "type": "scalar",
              "providerRecordId": "body-1:visceral_fat",
              "measuredAt": "2026-04-19T07:00:00Z",
              "metricType": "visceral_fat",
              "value": 8.0
            },
            {
              "type": "scalar",
              "providerRecordId": "hr-1",
              "measuredAt": "2026-04-19T08:30:00Z",
              "metricType": "heart_rate",
              "value": 62
            },
            {
              "type": "sleep_summary",
              "providerRecordId": "sleep-summary-1",
              "startAt": "2026-04-18T22:30:00Z",
              "endAt": "2026-04-19T06:45:00Z",
              "timeInBedSeconds": 29700,
              "totalSleepSeconds": 21000,
              "lightSleepSeconds": 9000,
              "deepSleepSeconds": 6300,
              "remSleepSeconds": 5700,
              "sleepEfficiencyPercent": 88.5,
              "sleepLatencySeconds": 600,
              "wakeupLatencySeconds": 120,
              "wakeupDurationSeconds": 900,
              "wakeupCount": 2,
              "wasoSeconds": 300,
              "sleepScore": 88
            }
          ]
        }
        """.trimIndent()
}
