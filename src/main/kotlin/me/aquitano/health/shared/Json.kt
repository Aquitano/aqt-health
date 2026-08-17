package me.aquitano.health.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

val AppJson: Json = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    isLenient = false
    encodeDefaults = true
}

// Null-safe accessors for provider payloads: a non-primitive value (object/array where a
// scalar was expected) reads as null so the record is skipped instead of throwing.
fun JsonElement.primitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.primitiveOrNull()?.contentOrNull

fun JsonObject.longOrNull(key: String): Long? =
    this[key]?.primitiveOrNull()?.longOrNull

fun JsonObject.doubleOrNull(key: String): Double? =
    this[key]?.primitiveOrNull()?.doubleOrNull
