package me.aquitano.external.google

import com.google.devicesandservices.health.v4.DataPoint
import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import me.aquitano.health.api.dto.IngestionBatchRequest
import me.aquitano.health.api.dto.ScalarSample
import me.aquitano.health.api.dto.SleepSession
import me.aquitano.health.api.dto.SleepSummary
import me.aquitano.health.application.IngestionMappingService
import me.aquitano.health.shared.AppJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GoogleHealthNormalizerTest {
    private val normalizer = GoogleHealthNormalizer()

    @Test
    fun heartRateVariabilityBecomesAnRmssdSample() {
        val records =
            normalize(
                "heart-rate-variability",
                """
                {
                  "name": "users/me/dataTypes/heart-rate-variability/dataPoints/8812734",
                  "heartRateVariability": {
                    "sampleTime": { "physicalTime": "2026-04-01T03:15:00Z" },
                    "rootMeanSquareOfSuccessiveDifferencesMilliseconds": 42.7,
                    "standardDeviationMilliseconds": 55.1
                  }
                }
                """,
            )

        val sample = assertIs<ScalarSample>(records.single())
        assertEquals("users/me/dataTypes/heart-rate-variability/dataPoints/8812734", sample.providerRecordId)
        assertEquals("2026-04-01T03:15:00Z", sample.measuredAt)
        assertEquals("hrv_rmssd", sample.metricType)
        assertEquals(42.7, sample.value)
    }

    @Test
    fun respiratoryRateSleepSummaryBecomesASleepRespiratoryRateSample() {
        val records =
            normalize(
                "respiratory-rate-sleep-summary",
                """
                {
                  "name": "users/me/dataTypes/respiratory-rate-sleep-summary/dataPoints/5521",
                  "respiratoryRateSleepSummary": {
                    "sampleTime": { "physicalTime": "2026-04-01T06:00:00Z" },
                    "deepSleepStats": { "breathsPerMinute": 13.2 },
                    "lightSleepStats": { "breathsPerMinute": 14.1 },
                    "remSleepStats": { "breathsPerMinute": 15.0 },
                    "fullSleepStats": { "breathsPerMinute": 14.4, "standardDeviation": 0.8 }
                  }
                }
                """,
            )

        val sample = assertIs<ScalarSample>(records.single())
        assertEquals("users/me/dataTypes/respiratory-rate-sleep-summary/dataPoints/5521", sample.providerRecordId)
        assertEquals("respiratory_rate", sample.metricType)
        assertEquals("sleep", sample.context)
        assertEquals(14.4, sample.value)
    }

    @Test
    fun sleepPointAlsoEmitsASummaryUnderItsOwnRecordId() {
        val records = normalize("sleep", sleepPoint(nap = false))

        assertEquals(2, records.size)
        assertEquals("users/me/dataTypes/sleep/dataPoints/77310", assertIs<SleepSession>(records[0]).providerRecordId)
        assertEquals(
            SleepSummary(
                providerRecordId = "users/me/dataTypes/sleep/dataPoints/77310:summary",
                startAt = "2026-03-31T22:30:00Z",
                endAt = "2026-04-01T06:30:00Z",
                timeInBedSeconds = 28_800,
                totalSleepSeconds = 26_400,
                lightSleepSeconds = 14_400,
                deepSleepSeconds = 5_400,
                remSleepSeconds = 6_600,
                sleepEfficiencyPercent = 440 * 100.0 / 480,
                sleepLatencySeconds = 720,
                wakeupLatencySeconds = 180,
                wakeupDurationSeconds = 2_400,
                wakeupCount = 14,
                remEpisodesCount = 5,
            ),
            records[1],
        )
        assertAcceptedByIngestion(records)
    }

    @Test
    fun napEmitsNoSummary() {
        val records = normalize("sleep", sleepPoint(nap = true))

        assertIs<SleepSession>(records.single())
    }

    private fun normalize(
        dataType: String,
        protoJson: String,
    ) = normalizer
        .normalize(GoogleHealthFetchResult(dataType, emptyList(), listOf(dataPointJson(protoJson))))

    /** Round-trips through the generated proto so a misspelled field fails the test, as the API would. */
    private fun dataPointJson(protoJson: String): JsonObject {
        val builder = DataPoint.newBuilder()
        JsonFormat.parser().merge(protoJson.trimIndent(), builder)
        return AppJson.parseToJsonElement(JsonFormat.printer().print(builder.build())).jsonObject
    }

    private fun sleepPoint(nap: Boolean): String =
        """
        {
          "name": "users/me/dataTypes/sleep/dataPoints/77310",
          "sleep": {
            "interval": {
              "startTime": "2026-03-31T22:30:00Z",
              "endTime": "2026-04-01T06:30:00Z"
            },
            "type": "STAGES",
            "stages": [
              { "type": "AWAKE", "startTime": "2026-03-31T22:30:00Z", "endTime": "2026-03-31T22:42:00Z" },
              { "type": "LIGHT", "startTime": "2026-03-31T22:42:00Z", "endTime": "2026-04-01T06:27:00Z" },
              { "type": "AWAKE", "startTime": "2026-04-01T06:27:00Z", "endTime": "2026-04-01T06:30:00Z" }
            ],
            "metadata": { "processed": true, "mainSleep": ${!nap}, "nap": $nap },
            "summary": {
              "minutesInSleepPeriod": "480",
              "minutesAfterWakeUp": "3",
              "minutesToFallAsleep": "12",
              "minutesAsleep": "440",
              "minutesAwake": "40",
              "stagesSummary": [
                { "type": "AWAKE", "minutes": "40", "count": "14" },
                { "type": "LIGHT", "minutes": "240", "count": "22" },
                { "type": "DEEP", "minutes": "90", "count": "4" },
                { "type": "REM", "minutes": "110", "count": "5" }
              ]
            }
          }
        }
        """

    private fun assertAcceptedByIngestion(records: List<me.aquitano.health.api.dto.IngestionRecord>) {
        IngestionMappingService().validateAndMap(
            IngestionBatchRequest(
                provider = GOOGLE_HEALTH_PROVIDER_CODE,
                providerInstanceId = "google-health-me",
                ingestedAt = "2026-04-01T10:00:00Z",
                sourcePayload = buildJsonObject { },
                records = records,
            ),
        )
    }
}
