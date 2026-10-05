package me.aquitano.health.application.metric.common

import me.aquitano.health.domain.RequestValidationException
import me.aquitano.health.domain.ValidationIssue
import me.aquitano.health.domain.ValidationIssueCodes
import me.aquitano.health.shared.Cursor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class QueryParams(
    private val values: Map<String, String?>,
) {
    fun optional(name: String): String? = values[name]?.takeIf { it.isNotBlank() }

    fun required(name: String): String = optional(name) ?: throw RequestValidationException(field = name, code = ValidationIssueCodes.Required, message = "is required")

    fun instant(name: String): Instant? = parsed(name, "an ISO-8601 instant", Instant::parse)

    fun date(name: String): LocalDate? = parsed(name, "an ISO-8601 date", LocalDate::parse)

    fun dateOrToday(
        name: String,
        now: Instant,
        timezone: ZoneId = ZoneOffset.UTC,
    ): LocalDate? =
        parsed(name, "an ISO-8601 date or today") {
            if (it == "today") now.atZone(timezone).toLocalDate() else LocalDate.parse(it)
        }

    fun timezone(name: String = "timezone"): ZoneId = parsed(name, "an IANA timezone", ZoneId::of) ?: ZoneOffset.UTC

    fun requiredDate(name: String): LocalDate = date(name) ?: throw RequestValidationException(field = name, code = ValidationIssueCodes.Required, message = "is required")

    internal fun boolean(spec: BooleanParamSpec): Boolean = boolean(spec.name, spec.default)

    fun boolean(
        name: String,
        default: Boolean,
    ): Boolean = parsed(name, "true or false") { it.lowercase().toBooleanStrict() } ?: default

    internal fun int(spec: IntParamSpec): Int {
        val parsed = parsed(spec.name, "an integer", String::toInt) ?: return spec.default
        if (parsed !in spec.min..spec.max) {
            throw RequestValidationException(field = spec.name, code = ValidationIssueCodes.OutOfRange, message = "must be between ${spec.min} and ${spec.max}")
        }
        return parsed
    }

    private fun <T> parsed(
        name: String,
        expected: String,
        parse: (String) -> T,
    ): T? =
        optional(name)?.let { value ->
            runCatching { parse(value) }.getOrElse {
                throw RequestValidationException(field = name, code = ValidationIssueCodes.InvalidFormat, message = "must be $expected")
            }
        }

    fun order(default: String = Orders.ASC): String {
        val value = optional("order") ?: return default
        val normalized = value.lowercase()
        if (normalized != Orders.ASC && normalized != Orders.DESC) {
            throw RequestValidationException(field = "order", code = ValidationIssueCodes.UnsupportedValue, message = "must be asc or desc")
        }
        return normalized
    }

    /** Decodes the cursor parameter, rejecting cursors issued under a different order. */
    fun cursor(order: String): Cursor? = optional("cursor")?.let { Cursor.decode(it, expectedOrder = order) }

    fun rejectLatest() {
        if (boolean("latest", default = false)) {
            throw RequestValidationException(field = "latest", code = ValidationIssueCodes.UnsupportedValue, message = "latest is not supported for this endpoint")
        }
    }

    fun rejectLatestOverrides(message: String = "cannot be combined with latest=true") {
        val invalidFields =
            listOf("limit", "order", "cursor")
                .filter { optional(it) != null }
        if (invalidFields.isNotEmpty()) {
            throw RequestValidationException(
                invalidFields.map {
                    ValidationIssue(
                        field = it,
                        code = ValidationIssueCodes.InvalidState,
                        message = message,
                    )
                },
            )
        }
    }
}
