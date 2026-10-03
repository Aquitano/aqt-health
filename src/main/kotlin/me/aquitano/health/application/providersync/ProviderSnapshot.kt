package me.aquitano.health.application.providersync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.aquitano.health.shared.AppJson
import java.security.MessageDigest
import java.util.HexFormat

/** Ignore response envelopes and record ordering, but retain every raw and normalized record. */
internal fun ProviderFetchedBatch.contentHash(): String {
    val digest = MessageDigest.getInstance("SHA-256")

    fun addRecords(records: List<String>) {
        records.sorted().forEach {
            digest.update(it.toByteArray(Charsets.UTF_8))
            digest.update(10.toByte())
        }
        digest.update(0.toByte())
    }
    addRecords(records.map { AppJson.encodeToString(it) })
    addRecords(sourceRecords.map { canonicalJson(it).toString() })
    return HexFormat.of().formatHex(digest.digest())
}

private fun canonicalJson(value: JsonElement): JsonElement =
    when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonicalJson(it.value) })
        is JsonArray -> JsonArray(value.map(::canonicalJson))
        else -> value
    }
