package me.aquitano.health.application

import me.aquitano.health.api.dto.ScalarSample
import me.aquitano.health.domain.ScalarMetricRegistry
import me.aquitano.health.domain.ScalarSampleRecord
import me.aquitano.health.domain.ValidationIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ScalarMetricRegistry descriptor is the single source of truth for the one scalar
 * record type: ranges, units, contexts, and segment support all come from it.
 */
class IngestionScalarRecordMappersTest {
    private val measuredAt = "2026-06-01T08:00:00Z"

    private fun map(
        metricType: String,
        value: Double,
        unit: String? = null,
        context: String? = null,
        segment: String? = null,
        issues: MutableList<ValidationIssue> = mutableListOf(),
    ): ScalarSampleRecord? =
        mapScalarSample(
            "records[0]",
            ScalarSample(
                measuredAt = measuredAt,
                metricType = metricType,
                value = value,
                unit = unit,
                context = context,
                segment = segment,
            ),
            issues,
        )

    @Test
    fun registryRangesBoundEveryMetricFamily() {
        val inRange = listOf(
            Triple("heart_rate", 25.0, "resting"),
            Triple("heart_rate", 250.0, "resting"),
            Triple("respiratory_rate", 5.0, null),
            Triple("respiratory_rate", 80.0, null),
            Triple("hrv_rmssd", 500.0, null),
            Triple("body_fat", 100.0, null),
            Triple("pulse_wave_velocity", 7.5, null),
            Triple("fat_mass", 12.3, null),
            // Zero is inside the range for cellular water compartments.
            Triple("intracellular_water", 0.0, null),
        )
        inRange.forEach { (metricType, value, context) ->
            val record = map(metricType, value, context = context)
            assertNotNull(record, "$metricType $value should be accepted")
            assertEquals(metricType, record.values.single().metricType)
        }

        val outOfRange = listOf(
            Triple("heart_rate", 24.0, "resting"),
            Triple("heart_rate", 251.0, "resting"),
            Triple("respiratory_rate", 4.0, null),
            Triple("respiratory_rate", 81.0, null),
            Triple("hrv_rmssd", 500.1, null),
            Triple("hrv_rmssd", 0.0, null),
            Triple("body_fat", 100.1, null),
            Triple("weight", 0.0, null),
            Triple("pulse_wave_velocity", 0.0, null),
            Triple("fat_mass", 0.0, null),
        )
        outOfRange.forEach { (metricType, value, context) ->
            assertNull(map(metricType, value, context = context), "$metricType $value should be rejected")
        }
    }

    @Test
    fun unitIsOptionalAndFilledFromTheRegistry() {
        val record = assertNotNull(map("weight", 80.0))
        assertEquals(ScalarMetricRegistry.get("weight").unit, record.values.single().unit)

        val issues = mutableListOf<ValidationIssue>()
        assertNull(map("weight", 80.0, unit = "lbs", issues = issues))
        assertTrue(issues.any { it.field == "records[0].unit" })
    }

    @Test
    fun contextDefaultsToUnknownOnlyWhereTheMetricSupportsIt() {
        assertEquals("unknown", assertNotNull(map("respiratory_rate", 14.0)).values.single().context)
        assertNull(assertNotNull(map("weight", 80.0)).values.single().context)

        val issues = mutableListOf<ValidationIssue>()
        assertNull(map("weight", 80.0, context = "resting", issues = issues))
        assertTrue(issues.any { it.field == "records[0].context" })
    }

    @Test
    fun segmentsAreRejectedForMetricsThatDoNotSupportThem() {
        assertEquals(
            "left_arm",
            assertNotNull(map("segmental_muscle_mass", 3.2, segment = "left_arm")).values.single().segment,
        )

        val unsupportedMetric = mutableListOf<ValidationIssue>()
        assertNull(map("fat_mass", 12.3, segment = "left_arm", issues = unsupportedMetric))
        assertTrue(unsupportedMetric.any { it.field == "records[0].segment" })

        val unsupportedSegment = mutableListOf<ValidationIssue>()
        assertNull(map("segmental_muscle_mass", 3.2, segment = "left_hand", issues = unsupportedSegment))
        assertTrue(unsupportedSegment.any { it.field == "records[0].segment" })
    }

    @Test
    fun unknownMetricTypesAreRejected() {
        val issues = mutableListOf<ValidationIssue>()
        assertNull(map("not_a_metric", 1.0, issues = issues))
        assertTrue(issues.any { it.field == "records[0].metricType" })
    }
}
