package me.aquitano.health.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.aquitano.health.domain.RequestValidationException
import me.aquitano.health.domain.ValidationIssueCodes
import java.util.Base64

@Serializable
enum class SortDirection {
    @SerialName("asc")
    Asc,

    @SerialName("desc")
    Desc,
    ;

    val wireName: String get() = serializer().descriptor.getElementName(ordinal)

    companion object {
        // Lazy because the plugin-generated serializer behind wireName is initialised after this companion.
        private val byWireName by lazy { entries.associateBy { it.wireName } }

        fun fromWireName(value: String): SortDirection? = byWireName[value]
    }
}

/**
 * Opaque keyset-pagination cursor. Encodes the sort value and row id of the last item of a
 * page plus the order it was produced under; decoding rejects a cursor whose order no longer
 * matches the request, so clients cannot silently mix pagination directions.
 */
@Serializable
data class Cursor(
    /** Sort value of the last row (ISO timestamp, date, or numeric string). */
    @SerialName("s") val sortValue: String,
    /** Row id of the last row; tie-break for equal sort values. */
    @SerialName("id") val lastId: Long,
    @SerialName("o") val order: SortDirection,
) {
    fun encode(): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(AppJson.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8))

    companion object {
        fun decode(
            value: String,
            expectedOrder: SortDirection,
        ): Cursor {
            val cursor =
                runCatching {
                    val json = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
                    AppJson.decodeFromString(serializer(), json)
                }.getOrElse {
                    throw RequestValidationException(field = "cursor", code = ValidationIssueCodes.InvalidFormat, message = "is not a valid cursor")
                }
            if (cursor.order != expectedOrder) {
                throw RequestValidationException(field = "cursor", code = ValidationIssueCodes.InvalidState, message = "was issued for order=${cursor.order.wireName} and cannot be used with this request")
            }
            return cursor
        }
    }
}
