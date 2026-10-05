package me.aquitano.health.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReplayScope(
    val stored: String,
    val includesProjections: Boolean,
    val includesDerived: Boolean,
) {
    @SerialName("projections")
    Projections("projections", includesProjections = true, includesDerived = false),

    @SerialName("derived")
    Derived("derived", includesProjections = false, includesDerived = true),

    @SerialName("all")
    All("all", includesProjections = true, includesDerived = true),
    ;

    companion object {
        fun fromStored(value: String): ReplayScope =
            entries.firstOrNull { it.stored == value }
                ?: error("Unknown replay scope '$value'")
    }
}
