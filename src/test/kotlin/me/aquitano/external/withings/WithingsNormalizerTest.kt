package me.aquitano.external.withings

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.aquitano.health.api.dto.ActivitySummary
import me.aquitano.health.api.dto.BloodPressure
import me.aquitano.health.api.dto.IngestionBatchRequest
import me.aquitano.health.api.dto.ScalarSample
import me.aquitano.health.api.dto.SleepSession
import me.aquitano.health.api.dto.SleepSummary
import me.aquitano.health.api.dto.StepInterval
import me.aquitano.health.application.IngestionMappingService
import me.aquitano.health.application.providersync.SyncWindow
import me.aquitano.health.application.providersync.collapseDuplicateProviderRecordIds
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WithingsNormalizerTest {
    private val normalizer = WithingsNormalizer()

    /** Only sleep is window-sensitive, so everything else normalizes with the window wide open. */
    private fun normalize(fetchResult: WithingsFetchResult) = normalizer.normalize(fetchResult, SyncWindow(Instant.MIN, Instant.MAX))

    private fun dayWindow(day: String) =
        SyncWindow(
            Instant.parse("${day}T00:00:00Z"),
            Instant.parse("${day}T00:00:00Z").plus(Duration.ofDays(1)),
        )

    @Test
    fun measuresConvertUnitsIntoOneScalarSamplePerMetric() {
        val result =
            normalize(
                fetchResult(
                    "measures",
                    buildJsonObject {
                        put("grpid", 123)
                        put("date", 1775001600)
                        putJsonArray("measures") {
                            addMeasure(type = 1, value = 80136, unit = -3)
                            addMeasure(type = 6, value = 214, unit = -1)
                            addMeasure(type = 76, value = 402, unit = -1)
                            addMeasure(type = 77, value = 40068, unit = -3)
                            addMeasure(type = 135, value = 98, unit = 0)
                            addMeasure(type = 168, value = 172, unit = -1)
                            addMeasure(type = 169, value = 228, unit = -1)
                            addMeasure(type = 170, value = 9, unit = 0)
                            addMeasure(type = 226, value = 1650, unit = 0)
                        }
                    },
                ),
            )

        val samples = result.records.filterIsInstance<ScalarSample>().associateBy { it.metricType }
        assertEquals(
            setOf("weight", "body_fat", "muscle", "water", "extracellular_water", "intracellular_water", "visceral_fat", "basal_metabolic_rate"),
            samples.keys,
        )
        assertEquals("withings:measure:123:weight", samples.getValue("weight").providerRecordId)
        assertEquals(80.136, samples.getValue("weight").value, 0.000001)
        assertEquals(21.4, samples.getValue("body_fat").value, 0.000001)
        assertEquals(40.2, samples.getValue("muscle").value, 0.000001)
        assertEquals(50.0, samples.getValue("water").value, 0.000001)
        assertEquals(17.2, samples.getValue("extracellular_water").value, 0.000001)
        assertEquals(22.8, samples.getValue("intracellular_water").value, 0.000001)
        assertEquals(9.0, samples.getValue("visceral_fat").value, 0.000001)
        assertEquals(1650.0, samples.getValue("basal_metabolic_rate").value, 0.000001)
    }

    @Test
    fun aRepeatedMeasureTypeCollapsesToOneSampleInsteadOfFailingTheBatch() {
        val result =
            normalize(
                fetchResult(
                    "measures",
                    buildJsonObject {
                        put("grpid", 987)
                        put("date", 1775001600)
                        putJsonArray("measures") {
                            addMeasure(type = 1, value = 80136, unit = -3)
                            addMeasure(type = 1, value = 80500, unit = -3)
                        }
                    },
                ),
            )

        val sample = assertIs<ScalarSample>(result.records.single())
        assertEquals("withings:measure:987:weight", sample.providerRecordId)
        // Last value wins, matching how the per-field accumulators used to resolve repeats.
        assertEquals(80.5, sample.value, 0.000001)
        assertAcceptedByIngestion(result.records)
    }

    @Test
    fun segmentalMeasuresAreKeyedByZoneAndARepeatedZoneCollapses() {
        val result =
            normalize(
                fetchResult(
                    "measures",
                    buildJsonObject {
                        put("grpid", 654)
                        put("date", 1775001600)
                        putJsonArray("measures") {
                            addSegmentalMeasure(type = 175, value = 32, unit = -1, zone = "left_arm")
                            addSegmentalMeasure(type = 175, value = 34, unit = -1, zone = "left_arm")
                            addSegmentalMeasure(type = 175, value = 36, unit = -1, zone = "right_arm")
                        }
                    },
                ),
            )

        val samples = result.records.filterIsInstance<ScalarSample>()
        assertEquals(
            listOf(
                "withings:measure:654:segmental_muscle_mass:left_arm",
                "withings:measure:654:segmental_muscle_mass:right_arm",
            ),
            samples.map { it.providerRecordId },
        )
        assertEquals(listOf("left_arm", "right_arm"), samples.map { it.segment })
        assertEquals(3.4, samples[0].value, 0.000001)
        assertAcceptedByIngestion(result.records)
    }

    @Test
    fun activityFromTwoTrackingDevicesCollapsesToOneRecordPerDate() {
        // getactivity returns an entry per tracking device, so a user with a watch and the phone
        // tracker gets the same date twice. Both entries normalize to the same date-keyed ids; the
        // sync pipeline collapses them the same way before ingestion, last entry winning.
        val result =
            normalize(
                fetchResult(
                    "activity",
                    buildJsonObject {
                        put("date", "2026-04-01")
                        put("deviceid", "watch-device")
                        put("brand", 18)
                        put("steps", 8000)
                        put("calories", 420)
                    },
                    buildJsonObject {
                        put("date", "2026-04-01")
                        put("deviceid", "phone-tracker")
                        put("brand", 1)
                        put("steps", 300)
                        put("calories", 15)
                    },
                ),
            )

        val records = result.records.collapseDuplicateProviderRecordIds()
        assertEquals(
            listOf("withings:activity:2026-04-01", "withings:activity:2026-04-01:summary"),
            records.map { it.providerRecordId },
        )
        assertEquals(300, records.filterIsInstance<StepInterval>().single().steps)
        assertAcceptedByIngestion(records)
    }

    @Test
    fun activityCreatesAUtcDayStepIntervalAndASummary() {
        val result =
            normalize(
                fetchResult(
                    "activity",
                    buildJsonObject {
                        put("date", "2026-04-01")
                        put("steps", 1234)
                        put("distance", 800.5)
                        put("calories", 310.0)
                        put("totalcalories", 2100.0)
                        put("elevation", 15.0)
                        put("soft", 20)
                        put("moderate", 30)
                        put("intense", 10)
                        put("active", 60)
                        put("hr_average", 74)
                        put("hr_min", 58)
                        put("hr_max", 132)
                    },
                ),
            )

        val steps = result.records.filterIsInstance<StepInterval>().single()
        assertEquals("withings:activity:2026-04-01", steps.providerRecordId)
        assertEquals("2026-04-01T00:00:00Z", steps.startAt)
        assertEquals("2026-04-02T00:00:00Z", steps.endAt)
        assertEquals(1234, steps.steps)
        val summary = result.records.filterIsInstance<ActivitySummary>().single()
        assertEquals("withings:activity:2026-04-01:summary", summary.providerRecordId)
        assertEquals(800.5, summary.distanceMeters!!, 0.000001)
        assertEquals(310.0, summary.activeEnergyKcal!!, 0.000001)
        assertEquals(2100.0, summary.totalEnergyKcal!!, 0.000001)
        assertEquals(15.0, summary.elevationMeters!!, 0.000001)
        assertEquals(60, summary.activeMinutes)
        assertEquals(74, summary.averageHeartRateBpm)
    }

    @Test
    fun measurePulseWithoutBloodPressureCreatesStandaloneHeartRate() {
        val result =
            normalize(
                fetchResult(
                    "measures",
                    buildJsonObject {
                        put("grpid", 456)
                        put("date", 1775001600)
                        putJsonArray("measures") {
                            addMeasure(type = 11, value = 62, unit = 0)
                        }
                    },
                ),
            )

        val heartRate = assertIs<ScalarSample>(result.records.single())
        assertEquals("withings:measure:456:heart_rate", heartRate.providerRecordId)
        assertEquals("2026-04-01T00:00:00Z", heartRate.measuredAt)
        assertEquals("heart_rate", heartRate.metricType)
        assertEquals(62.0, heartRate.value, 0.000001)
        assertEquals("general", heartRate.context)
    }

    @Test
    fun bloodPressureClaimsTheHeartRateRegardlessOfMeasureOrder() {
        listOf(true, false).forEach { heartRateFirst ->
            val result =
                normalize(
                    fetchResult(
                        "measures",
                        buildJsonObject {
                            put("grpid", 654)
                            put("date", 1775001600)
                            putJsonArray("measures") {
                                if (heartRateFirst) addMeasure(type = 11, value = 62, unit = 0)
                                addMeasure(type = 9, value = 80, unit = 0)
                                addMeasure(type = 10, value = 120, unit = 0)
                                if (!heartRateFirst) addMeasure(type = 11, value = 62, unit = 0)
                            }
                        },
                    ),
                )

            val bloodPressure = assertIs<BloodPressure>(result.records.single())
            assertEquals(120, bloodPressure.systolicMmhg)
            assertEquals(80, bloodPressure.diastolicMmhg)
            assertEquals(62, bloodPressure.heartRateBpm)
            assertTrue(result.records.filterIsInstance<ScalarSample>().isEmpty())
        }
    }

    @Test
    fun incompleteBloodPressureIsDroppedButItsRawPageIsKept() {
        val result =
            normalize(
                fetchResult(
                    "measures",
                    buildJsonObject {
                        put("grpid", 789)
                        put("date", 1775001600)
                        putJsonArray("measures") {
                            addMeasure(type = 10, value = 120, unit = 0)
                        }
                    },
                ),
            )

        assertTrue(result.records.isEmpty())
        assertEquals(1, result.sourcePayload["pages"]!!.jsonArray.size)
    }

    @Test
    fun sleepSummaryCreatesAggregateSleepMetrics() {
        val result =
            normalize(
                fetchResult(
                    "sleep-summary",
                    buildJsonObject {
                        put("startdate", 1775001600)
                        put("enddate", 1775023200)
                        putJsonObject("data") {
                            put("total_timeinbed", 21600)
                            put("total_sleep_time", 18000)
                            put("lightsleepduration", 9000)
                            put("deepsleepduration", 3600)
                            put("remsleepduration", 5400)
                            put("sleep_efficiency", 83.3)
                            put("sleep_latency", 600)
                            put("wakeup_latency", 120)
                            put("wakeupduration", 900)
                            put("wakeupcount", 2)
                            put("waso", 300)
                            put("sleep_score", 88)
                        }
                    },
                ),
            )

        val summary = assertIs<SleepSummary>(result.records.single())
        assertEquals("withings:sleep-summary:1775001600:1775023200:summary", summary.providerRecordId)
        assertEquals(21600, summary.timeInBedSeconds)
        assertEquals(18000, summary.totalSleepSeconds)
        assertEquals(83.3, summary.sleepEfficiencyPercent!!, 0.000001)
        assertEquals(88, summary.sleepScore)
    }

    @Test
    fun highFrequencySleepReadsTimestampKeyedVitalsAndTimestampedStages() {
        val result =
            normalize(
                fetchResult(
                    "sleep",
                    buildJsonObject {
                        put("timestamp", 1775001600)
                        put("state", 1)
                        putJsonObject("hr") {
                            put("1775001600", 58)
                            put("1775001660", 57)
                        }
                        putJsonObject("rr") { put("1775001600", 14) }
                        putJsonObject("rmssd") { put("1775001600", 42) }
                    },
                    buildJsonObject {
                        put("timestamp", 1775005200)
                        put("state", 2)
                        putJsonObject("hr") { put("1775005200", 56) }
                    },
                ),
            )

        val sleep = assertIs<SleepSession>(result.records.first())
        assertEquals(1, sleep.stages.size)
        assertEquals("light", sleep.stages[0].stage)
        val samples = result.records.filterIsInstance<ScalarSample>()
        val heartRates = samples.filter { it.metricType == "heart_rate" }
        assertEquals(listOf(58.0, 57.0, 56.0), heartRates.map { it.value })
        assertEquals("withings:sleep:hr:1775001660", heartRates[1].providerRecordId)
        assertEquals("2026-04-01T00:01:00Z", heartRates[1].measuredAt)
        assertEquals("sleep", heartRates.first().context)
        val respiratoryRate = samples.single { it.metricType == "respiratory_rate" }
        assertEquals("withings:sleep:rr:1775001600", respiratoryRate.providerRecordId)
        assertEquals(14.0, respiratoryRate.value, 0.000001)
        val hrv = samples.single { it.metricType == "hrv_rmssd" }
        assertEquals("withings:sleep:rmssd:1775001600", hrv.providerRecordId)
        assertEquals(42.0, hrv.value, 0.000001)
    }

    @Test
    fun sleepSeriesValueUsesValueAsState() {
        val result =
            normalize(
                fetchResult(
                    "sleep",
                    buildJsonObject {
                        put("timestamp", 1775001600)
                        put("value", 1)
                    },
                    buildJsonObject {
                        put("timestamp", 1775005200)
                        put("value", 2)
                    },
                ),
            )

        val sessions = result.records.filterIsInstance<SleepSession>()
        assertEquals(1, sessions.size)
        assertEquals(1, sessions.first().stages.size)
        assertEquals(
            "light",
            sessions
                .first()
                .stages
                .first()
                .stage,
        )
    }

    @Test
    fun sleepSeriesIgnoresNestedValueObjects() {
        val result =
            normalize(
                fetchResult(
                    "sleep",
                    buildJsonObject {
                        put("timestamp", 1775001600)
                        put(
                            "value",
                            buildJsonObject {
                                put("state", 1)
                            },
                        )
                    },
                    buildJsonObject {
                        put("timestamp", 1775005200)
                        put("value", 2)
                    },
                ),
            )

        val sessions = result.records.filterIsInstance<SleepSession>()
        assertEquals(1, sessions.size)
        assertEquals(
            "light",
            sessions
                .first()
                .stages
                .first()
                .stage,
        )
    }

    @Test
    fun sleepSeriesUsesStartDateAsTimestampWhenEndDateMissing() {
        val result =
            normalize(
                fetchResult(
                    "sleep",
                    buildJsonObject {
                        put("startdate", 1775001600)
                        put("state", 1)
                    },
                    buildJsonObject {
                        put("startdate", 1775005200)
                        put("state", 2)
                    },
                ),
            )

        val sessions = result.records.filterIsInstance<SleepSession>()
        assertEquals(1, sessions.size)
        assertEquals(1, sessions.first().stages.size)
        assertEquals(
            "light",
            sessions
                .first()
                .stages
                .first()
                .stage,
        )
    }

    @Test
    fun sleepSessionCrossingUtcMidnightBelongsToTheWindowItEndsIn() {
        val night =
            fetchResult(
                "sleep",
                buildJsonObject {
                    put("startdate", 1775077200) // 2026-04-01T21:00:00Z
                    put("enddate", 1775088000)
                    put("state", 1)
                    putJsonObject("hr") { put("1775077200", 58) }
                },
                buildJsonObject {
                    put("startdate", 1775088000) // 2026-04-02T00:00:00Z
                    put("enddate", 1775106000)
                    put("state", 2)
                    putJsonObject("hr") { put("1775088000", 56) }
                },
            )

        val nightStarted = normalizer.normalize(night, dayWindow("2026-04-01"))
        val nightEnded = normalizer.normalize(night, dayWindow("2026-04-02"))

        assertTrue(nightStarted.records.filterIsInstance<SleepSession>().isEmpty())
        val session = nightEnded.records.filterIsInstance<SleepSession>().single()
        assertEquals("withings:sleep:1775077200:1775106000", session.providerRecordId)
        assertEquals("2026-04-01T21:00:00Z", session.startAt)
        assertEquals("2026-04-02T05:00:00Z", session.endAt)
        assertEquals(listOf("light", "deep"), session.stages.map { it.stage })
        assertEquals(
            listOf("2026-04-02T00:00:00Z"),
            nightEnded.records.filterIsInstance<ScalarSample>().map { it.measuredAt },
        )
    }

    @Test
    fun highFrequencySleepSplitsSessionsAcrossLargeGaps() {
        val result =
            normalize(
                fetchResult(
                    "sleep",
                    buildJsonObject {
                        put("timestamp", "2026-04-01T00:00:00Z")
                        put("state", 1)
                    },
                    buildJsonObject {
                        put("timestamp", "2026-04-01T01:00:00Z")
                        put("state", 2)
                    },
                    buildJsonObject {
                        put("timestamp", "2026-04-02T00:00:00Z")
                        put("state", 1)
                    },
                    buildJsonObject {
                        put("timestamp", "2026-04-02T01:00:00Z")
                        put("state", 3)
                    },
                ),
            )

        val sessions = result.records.filterIsInstance<SleepSession>()
        assertEquals(2, sessions.size)
        assertEquals("2026-04-01T00:00:00Z", sessions[0].startAt)
        assertEquals("2026-04-01T01:00:00Z", sessions[0].endAt)
        assertEquals(1, sessions[0].stages.size)
        assertEquals("2026-04-02T00:00:00Z", sessions[1].startAt)
        assertEquals("2026-04-02T01:00:00Z", sessions[1].endAt)
        assertEquals(1, sessions[1].stages.size)
    }

    @Test
    fun invalidAndZeroValuesAreSkipped() {
        val result =
            normalize(
                fetchResult(
                    "activity",
                    buildJsonObject {
                        put("date", "2026-04-01")
                        put("steps", 0)
                    },
                ),
            )

        assertTrue(result.records.isEmpty())
    }

    /** Ingestion rejects a whole batch that repeats a providerRecordId, so prove it takes these. */
    private fun assertAcceptedByIngestion(records: List<me.aquitano.health.api.dto.IngestionRecord>) {
        IngestionMappingService().validateAndMap(
            IngestionBatchRequest(
                provider = "withings",
                providerInstanceId = "scale-1",
                ingestedAt = "2026-04-01T10:00:00Z",
                sourcePayload = buildJsonObject { },
                records = records,
            ),
        )
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addSegmentalMeasure(
        type: Int,
        value: Int,
        unit: Int,
        zone: String,
    ) {
        add(
            buildJsonObject {
                put("type", type)
                put("value", value)
                put("unit", unit)
                put("zone", zone)
            },
        )
    }

    private fun fetchResult(
        dataType: String,
        vararg records: kotlinx.serialization.json.JsonObject,
    ): WithingsFetchResult =
        WithingsFetchResult(
            dataType = dataType,
            pages =
                listOf(
                    WithingsPage(
                        endpoint = "https://wbsapi.withings.net/v2/test",
                        action = dataType,
                        pageIndex = 0,
                        payload = buildJsonObject { put("status", 0) },
                    ),
                ),
            records = records.toList(),
        )

    private fun kotlinx.serialization.json.JsonArrayBuilder.addMeasure(
        type: Int,
        value: Int,
        unit: Int,
    ) {
        add(
            buildJsonObject {
                put("type", type)
                put("value", value)
                put("unit", unit)
            },
        )
    }
}
