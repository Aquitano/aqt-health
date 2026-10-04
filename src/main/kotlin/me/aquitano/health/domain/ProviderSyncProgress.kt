package me.aquitano.health.domain

import java.time.Instant

data class ProviderSyncItem(
    val dataType: String,
    val from: Instant,
    val to: Instant,
    val pageSize: Int? = null,
)

interface ProviderSyncProgressSink {
    suspend fun started(
        totalItems: Int,
        providerInstanceId: String,
    ) {}

    suspend fun itemStarted(item: ProviderSyncItem) {}

    suspend fun itemCompleted(item: ProviderSyncItem) {}

    companion object {
        val None = object : ProviderSyncProgressSink {}
    }
}
