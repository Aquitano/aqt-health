package me.aquitano.health.application

import me.aquitano.health.api.dto.ProviderSyncRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class IdempotencyTest {
    @Test
    fun dataTypeOrderAndDuplicatesDoNotChangeTheHash() {
        val request = ProviderSyncRequest(dataTypes = listOf("sleep", "heart_rate"))

        assertEquals(
            syncRequestHash(request),
            syncRequestHash(request.copy(dataTypes = listOf("heart_rate", "sleep"))),
        )
        assertEquals(
            syncRequestHash(request),
            syncRequestHash(request.copy(dataTypes = listOf("sleep", "heart_rate", "sleep"))),
        )
    }

    @Test
    fun differentDataTypesChangeTheHash() {
        assertNotEquals(
            syncRequestHash(ProviderSyncRequest(dataTypes = listOf("sleep"))),
            syncRequestHash(ProviderSyncRequest(dataTypes = listOf("sleep", "heart_rate"))),
        )
    }
}
