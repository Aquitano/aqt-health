package me.aquitano.health.application.metric.common

/**
 * Single source of truth for query-parameter contracts. Both the runtime parsers
 * ([QueryParams], QueryFilters) and the OpenAPI parameter builders consume these
 * specs, so documented defaults, limits, and enums cannot drift from validation.
 */
internal data class BooleanParamSpec(
    val name: String,
    val default: Boolean,
)

internal data class IntParamSpec(
    val name: String,
    val default: Int,
    val min: Int,
    val max: Int,
)

internal data class EnumParamSpec(
    val name: String,
    val values: List<String>,
    val default: String,
) {
    init {
        require(default in values) { "default '$default' must be one of $values" }
    }
}

internal object QueryParamSpecs {
    val includeSource = BooleanParamSpec("includeSource", default = false)
    val latest = BooleanParamSpec("latest", default = false)
    val raw = BooleanParamSpec("raw", default = false)

    val readLimit = IntParamSpec("limit", default = 500, min = 1, max = 5000)
    val adminLimit = IntParamSpec("limit", default = 100, min = 1, max = 1000)
    val periodDays = IntParamSpec("periodDays", default = 7, min = 1, max = 90)

    val order = EnumParamSpec("order", listOf(Orders.ASC, Orders.DESC), Orders.ASC)
}
