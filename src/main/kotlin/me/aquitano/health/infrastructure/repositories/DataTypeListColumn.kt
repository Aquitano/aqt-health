package me.aquitano.health.infrastructure.repositories

/**
 * Data type / metric type lists are stored as a single comma-separated text column across the
 * job and scheduled-sync tables. Decoding trims and drops blanks so a legacy value written with
 * spaces still round-trips to the same list.
 */
internal fun encodeDataTypes(dataTypes: List<String>): String =
    dataTypes.joinToString(",")

internal fun decodeDataTypes(value: String): List<String> =
    value.split(",").map { it.trim() }.filter { it.isNotBlank() }
