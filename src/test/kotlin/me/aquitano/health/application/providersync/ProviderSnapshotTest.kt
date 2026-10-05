package me.aquitano.health.application.providersync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.aquitano.health.api.dto.StepInterval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ProviderSnapshotTest {
    @Test
    fun recordOrderingDoesNotChangeDigestButMultiplicityDoes() {
        val first = StepInterval("a", "2026-04-01T08:00:00Z", "2026-04-01T09:00:00Z", 100)
        val second = first.copy(providerRecordId = "b", steps = 200)
        val rawFirst =
            buildJsonObject {
                put("id", "a")
                put("steps", 100)
            }
        val rawSecond =
            buildJsonObject {
                put("id", "b")
                put("steps", 200)
            }
        val original =
            ProviderFetchedBatch(
                pages = JsonArray(listOf(buildJsonObject { put("requestId", "first-request") })),
                sourceRecords = listOf(rawFirst, rawSecond),
                records = listOf(first, second),
            )
        assertEquals(
            original.contentHash(),
            original
                .copy(
                    records = listOf(second, first),
                    sourceRecords = listOf(rawSecond, rawFirst),
                    pages =
                        JsonArray(
                            listOf(
                                buildJsonObject { put("requestId", "another-request") },
                                buildJsonObject { put("requestId", "next-page") },
                            ),
                        ),
                ).contentHash(),
        )
        assertNotEquals(original.contentHash(), original.copy(records = listOf(first, second, second)).contentHash())
    }
}
