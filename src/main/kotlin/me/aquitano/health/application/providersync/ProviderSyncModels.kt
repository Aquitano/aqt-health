package me.aquitano.health.application.providersync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import me.aquitano.health.api.dto.IngestionRecord
import me.aquitano.health.domain.BatchStatus
import me.aquitano.health.domain.IngestionSnapshot
import me.aquitano.health.domain.ProviderAccountStatus
import java.time.Instant

data class SyncAccount(
    val id: Int,
    val providerCode: String,
    val providerUserId: String,
    val providerInstanceId: String,
    val encryptedAccessToken: String,
    val encryptedRefreshToken: String,
    val expiresAt: Instant,
    val accountStatus: ProviderAccountStatus,
)

data class ProviderAccessToken(
    val accessToken: String,
    val refreshToken: String,
)

data class RefreshedTokenSet(
    val accessToken: String,
    val refreshToken: String?,
    val tokenType: String,
    val expiresAt: Instant,
    val scope: String?,
)

/**
 * One window's upstream response: the raw response [pages], stored verbatim in the batch's source
 * payload, the raw [sourceRecords] read from them, and the [records] they normalize to.
 */
data class ProviderFetchedBatch(
    val pages: JsonArray,
    val sourceRecords: List<JsonObject>,
    val records: List<IngestionRecord>,
)

data class ExistingProviderBatch(
    val id: Int,
    val status: BatchStatus,
)

/**
 * Collapses records that repeat a providerRecordId, keeping the last occurrence of each in place.
 *
 * Providers hand out the same record twice inside one window: Withings `getactivity` returns an
 * entry per tracking device for a date, and overlapping pages resend rows. Ingestion rejects the
 * whole batch over a single duplicated id and that rejection is non-retryable, so an uncollapsed
 * batch parks the sync schedule instead of storing the day. Last-wins matches how a repeated
 * measure type collapses inside a Withings measure group.
 */
fun List<IngestionRecord>.collapseDuplicateProviderRecordIds(): List<IngestionRecord> {
    val lastIndexById = HashMap<String, Int>()
    forEachIndexed { index, record ->
        record.providerRecordId?.let { lastIndexById[it] = index }
    }
    return filterIndexed { index, record ->
        val id = record.providerRecordId ?: return@filterIndexed true
        lastIndexById[id] == index
    }
}

data class ProviderIngestionCommand(
    val providerCode: String,
    val providerInstanceId: String,
    val batchExternalId: String,
    val dataType: String,
    val ingestedAt: Instant,
    val sourcePayload: JsonObject,
    val records: List<IngestionRecord>,
    val snapshot: IngestionSnapshot,
)
