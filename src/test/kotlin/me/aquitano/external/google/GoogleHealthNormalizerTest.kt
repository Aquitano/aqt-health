package me.aquitano.external.google

import com.google.devicesandservices.health.v4.ReconciledDataPoint
import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import me.aquitano.health.api.dto.ActivitySummary
import me.aquitano.health.api.dto.DailyMetric
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
                GoogleHealthDataType.HeartRateVariability,
                """
                {
                  "dataPointName": "users/me/dataTypes/heart-rate-variability/dataPoints/8812734",
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
                GoogleHealthDataType.RespiratoryRateSleepSummary,
                """
                {
                  "dataPointName": "users/me/dataTypes/respiratory-rate-sleep-summary/dataPoints/5521",
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
        val records = normalize(GoogleHealthDataType.Sleep, sleepPoint(nap = false))

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
        val records = normalize(GoogleHealthDataType.Sleep, sleepPoint(nap = true))

        assertIs<SleepSession>(records.single())
    }

    @Test
    fun onlyTheMainSleepGetsASummaryWhenTheWindowHasOne() {
        val main = dataPointJson(sleepPoint(nap = false))
        val second = dataPointJson(sleepPoint(nap = false, id = "77311", mainSleep = false))

        val records = normalizer.normalize(GoogleHealthFetchResult(GoogleHealthDataType.Sleep, emptyList(), listOf(main, second)))

        assertEquals(listOf("users/me/dataTypes/sleep/dataPoints/77310:summary"), records.filterIsInstance<SleepSummary>().map { it.providerRecordId })
        assertEquals(2, records.filterIsInstance<SleepSession>().size)
    }

    @Test
    fun pointsWithoutAGoogleNameAreKeyedByTimeAloneSoAddedFieldsCannotDuplicateThem() {
        val heartRate =
            """{ "heartRate": { "sampleTime": { "physicalTime": "2026-04-01T08:30:00Z" }, "beatsPerMinute": "62" } }"""
        val withExtraField =
            """{ "heartRate": { "sampleTime": { "physicalTime": "2026-04-01T08:30:00Z", "utcOffset": "7200s" }, "beatsPerMinute": "62" } }"""

        val ids = listOf(heartRate, withExtraField).map { assertIs<ScalarSample>(normalize(GoogleHealthDataType.HeartRate, it).single()).providerRecordId }

        assertEquals(listOf("heart-rate:2026-04-01T08:30:00Z:none", "heart-rate:2026-04-01T08:30:00Z:none"), ids)
    }

    @Test
    fun differentReadingsAtOneInstantKeepSeparateIdsAndIdenticalCopiesCollapse() {
        val points =
            listOf("61", "62", "61").map {
                dataPointJson("""{ "heartRate": { "sampleTime": { "physicalTime": "2026-04-01T08:30:00Z" }, "beatsPerMinute": "$it" } }""")
            }

        fun idsByValue(points: List<JsonObject>) =
            normalizer
                .normalize(GoogleHealthFetchResult(GoogleHealthDataType.HeartRate, emptyList(), points))
                .map { assertIs<ScalarSample>(it) }
                .also { assertEquals(2, it.size) }
                .associate { it.value to it.providerRecordId }

        val ids = idsByValue(points)

        assertEquals(setOf(61.0, 62.0), ids.keys)
        assertEquals("heart-rate:2026-04-01T08:30:00Z:none", ids[61.0])
        assertEquals(ids, idsByValue(points.reversed()))
    }

    @Test
    fun zeroBodyFatIsDropped() {
        val records =
            normalize(
                GoogleHealthDataType.BodyFat,
                """{ "dataPointName": "users/me/dataTypes/body-fat/dataPoints/1", "bodyFat": { "sampleTime": { "physicalTime": "2026-04-01T07:00:00Z" }, "percentage": 0 } }""",
            )

        assertEquals(emptyList(), records)
    }

    @Test
    fun dailyMetricsMapEveryReportedValueAndDropImplausibleOnes() {
        val hrv =
            normalize(
                GoogleHealthDataType.DailyHeartRateVariability,
                """
                {
                  "dailyHeartRateVariability": {
                    "date": { "year": 2026, "month": 4, "day": 1 },
                    "averageHeartRateVariabilityMilliseconds": 41.25,
                    "nonRemHeartRateBeatsPerMinute": "52",
                    "deepSleepRootMeanSquareOfSuccessiveDifferencesMilliseconds": 38.5
                  }
                }
                """,
            )
        val oxygen =
            normalize(
                GoogleHealthDataType.DailyOxygenSaturation,
                """
                {
                  "dailyOxygenSaturation": {
                    "date": { "year": 2026, "month": 4, "day": 1 },
                    "averagePercentage": 96.4,
                    "lowerBoundPercentage": 12.0,
                    "upperBoundPercentage": 98.0
                  }
                }
                """,
            )

        assertEquals(
            listOf(
                DailyMetric("daily-heart-rate-variability:2026-04-01:nightly_hrv_rmssd", "2026-04-01", "nightly_hrv_rmssd", 41.25),
                DailyMetric("daily-heart-rate-variability:2026-04-01:deep_sleep_hrv_rmssd", "2026-04-01", "deep_sleep_hrv_rmssd", 38.5),
                DailyMetric("daily-heart-rate-variability:2026-04-01:non_rem_heart_rate", "2026-04-01", "non_rem_heart_rate", 52.0),
                DailyMetric("daily-oxygen-saturation:2026-04-01:oxygen_saturation", "2026-04-01", "oxygen_saturation", 96.4),
            ),
            hrv + oxygen,
        )
        assertAcceptedByIngestion(hrv + oxygen)
    }

    @Test
    fun activityRollUpsBecomeOneSummaryAndLevelsWithoutMinutesCountAsZero() {
        val records =
            normalizer.normalize(
                GoogleHealthFetchResult(
                    GoogleHealthDataType.Activity,
                    emptyList(),
                    listOf(
                        AppJson
                            .parseToJsonElement(
                                """
                                {
                                  "date": "2026-04-01",
                                  "distance": { "millimetersSum": "4589200" },
                                  "totalCalories": { "kcalSum": 2695.421165 },
                                  "activeMinutes": { "activeMinutesRollupByActivityLevel": [ { "activityLevel": "LIGHT", "activeMinutesSum": "171" }, { "activityLevel": "VIGOROUS", "activeMinutesSum": "37" } ] },
                                  "heartRate": { "beatsPerMinuteAvg": 78.6, "beatsPerMinuteMin": 47, "beatsPerMinuteMax": 152 }
                                }
                                """,
                            ).jsonObject,
                        AppJson.parseToJsonElement("""{ "date": "2026-04-02" }""").jsonObject,
                    ),
                ),
            )

        assertEquals(
            listOf(
                ActivitySummary(
                    providerRecordId = "activity:2026-04-01",
                    date = "2026-04-01",
                    distanceMeters = 4589.2,
                    totalEnergyKcal = 2695.421165,
                    softMinutes = 171,
                    moderateMinutes = 0,
                    intenseMinutes = 37,
                    activeMinutes = 37,
                    averageHeartRateBpm = 79,
                    minHeartRateBpm = 47,
                    maxHeartRateBpm = 152,
                ),
            ),
            records,
        )
        assertAcceptedByIngestion(records)
    }

    private fun normalize(
        dataType: GoogleHealthDataType,
        protoJson: String,
    ) = normalizer
        .normalize(GoogleHealthFetchResult(dataType, emptyList(), listOf(dataPointJson(protoJson))))

    /** Round-trips through the generated proto so a misspelled field fails the test, as the API would. */
    private fun dataPointJson(protoJson: String): JsonObject {
        val builder = ReconciledDataPoint.newBuilder()
        JsonFormat.parser().merge(protoJson.trimIndent(), builder)
        return AppJson.parseToJsonElement(JsonFormat.printer().print(builder.build())).jsonObject
    }

    private fun sleepPoint(
        nap: Boolean,
        id: String = "77310",
        mainSleep: Boolean = !nap,
    ): String =
        """
        {
          "dataPointName": "users/me/dataTypes/sleep/dataPoints/$id",
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
            "metadata": { "processed": true, "mainSleep": $mainSleep, "nap": $nap },
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
