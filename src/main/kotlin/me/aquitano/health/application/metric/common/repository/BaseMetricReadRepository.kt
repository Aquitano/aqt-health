package me.aquitano.health.application.metric.common.repository

import me.aquitano.health.api.dto.SourceMetadataResponse
import me.aquitano.health.domain.RequestValidationException
import me.aquitano.health.domain.ValidationIssue
import me.aquitano.health.domain.ValidationIssueCodes
import me.aquitano.health.infrastructure.database.tables.SourceInstancesTable
import me.aquitano.health.infrastructure.database.tables.SourcesTable
import me.aquitano.health.infrastructure.database.toDbTimestamp
import me.aquitano.health.shared.Cursor
import me.aquitano.health.shared.SortDirection
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Base class for metric-specific read-model repositories.
 *
 * These repositories build Exposed queries but never open transactions; the
 * calling service owns the transaction boundary. Extracts the common
 * query-building helpers that every metric repository needs (source metadata
 * resolution, provider filtering, condition composition, sort order
 * conversion) so that concrete repositories only contain table-specific
 * query logic.
 */
abstract class BaseMetricReadRepository {
    fun sourceMetadataFor(sourceIds: Set<Int>): Map<Int, SourceMetadataResponse> {
        if (sourceIds.isEmpty()) return emptyMap()
        return SourceInstancesTable
            .innerJoin(SourcesTable)
            .select(
                SourceInstancesTable.id,
                SourcesTable.code,
                SourceInstancesTable.providerInstanceId,
            ).where { SourceInstancesTable.id inList sourceIds }
            .associate {
                it[SourceInstancesTable.id].value to
                    SourceMetadataResponse(
                        provider = it[SourcesTable.code],
                        providerInstanceId = it[SourceInstancesTable.providerInstanceId],
                    )
            }
    }

    /**
     * Resolves source instance IDs that match the optional [provider] and
     * [providerInstanceId] filters.
     *
     * Returns `null` when neither filter is supplied, signalling that no
     * source filtering should be applied.
     */
    protected fun sourceInstanceIds(
        provider: String?,
        providerInstanceId: String?,
    ): List<Int>? {
        if (provider == null && providerInstanceId == null) return null
        return SourceInstancesTable
            .innerJoin(SourcesTable)
            .select(SourceInstancesTable.id)
            .where {
                combineConditions(
                    listOfNotNull(
                        provider?.let { SourcesTable.code eq it },
                        providerInstanceId?.let { SourceInstancesTable.providerInstanceId eq it },
                    ),
                )
            }.map { it[SourceInstancesTable.id].value }
    }

    protected fun ReadFilters.sourceInstanceIds(): List<Int>? = sourceInstanceIds(provider, providerInstanceId)

    protected fun List<Int>?.hasNoMatchingSources(): Boolean = this != null && isEmpty()

    protected fun <T> emptyReadResult(): Pair<List<T>, Map<Int, SourceMetadataResponse>> = emptyList<T>() to emptyMap()

    protected fun <T> emptyLatestResult(): Pair<T?, Map<Int, SourceMetadataResponse>> = null to emptyMap()

    protected fun <T, S> emptyTripleReadResult(): Triple<List<T>, Map<Int, List<S>>, Map<Int, SourceMetadataResponse>> = Triple(emptyList(), emptyMap(), emptyMap())

    /** The read's where clause, or null when the source filters match no source instance. */
    protected fun timestampConditions(
        filters: ReadFilters,
        sourceInstanceIdColumn: Column<Int>,
        fromColumn: Column<OffsetDateTime>,
        mode: TimeFilterMode = TimeFilterMode.StartAtInRange,
    ): Op<Boolean>? {
        val sourceIds = filters.sourceInstanceIds()
        if (sourceIds.hasNoMatchingSources()) return null
        val from = filters.from?.toDbTimestamp()
        val to = filters.to?.toDbTimestamp()
        val timeConditions =
            when (mode) {
                TimeFilterMode.StartAtInRange -> {
                    listOf(from?.let { fromColumn greaterEq it }, to?.let { fromColumn less it })
                }

                TimeFilterMode.BeforeFrom -> {
                    listOf(from?.let { fromColumn less it })
                }

                is TimeFilterMode.OverlapsWindow -> {
                    listOf(
                        from?.let { if (mode.inclusiveFrom) mode.toColumn greaterEq it else mode.toColumn greater it },
                        to?.let { fromColumn less it },
                    )
                }
            }
        return combineConditions(timeConditions.filterNotNull() + listOfNotNull(sourceIds?.let { sourceInstanceIdColumn inList it }))
    }

    /** The read's where clause, or null when the source filters match no source instance. */
    protected fun dateConditions(
        filters: ReadFilters,
        sourceInstanceIdColumn: Column<Int>,
        dateColumn: Column<LocalDate>,
    ): Op<Boolean>? {
        val sourceIds = filters.sourceInstanceIds()
        if (sourceIds.hasNoMatchingSources()) return null
        return combineConditions(
            listOfNotNull(
                filters.fromDate?.let { dateColumn greaterEq it },
                filters.toDate?.let { dateColumn lessEq it },
                sourceIds?.let { sourceInstanceIdColumn inList it },
            ),
        )
    }

    protected fun sourceMetadata(
        sourceInstanceIds: Set<Int>,
        includeSource: Boolean,
    ): Map<Int, SourceMetadataResponse> = if (includeSource) sourceMetadataFor(sourceInstanceIds) else emptyMap()

    protected fun <T> List<ResultRow>.mapWithSource(
        sourceInstanceId: Column<Int>,
        includeSource: Boolean,
        toItem: (row: ResultRow, source: SourceMetadataResponse?) -> T,
    ): List<T> {
        val metadata = sourceMetadata(mapTo(HashSet()) { it[sourceInstanceId] }, includeSource)
        return map { toItem(it, metadata[it[sourceInstanceId]]) }
    }

    /**
     * Combines a list of Exposed boolean conditions with `AND`.
     *
     * Returns [Op.TRUE] when the list is empty.
     */
    protected fun combineConditions(conditions: List<Op<Boolean>>): Op<Boolean> = conditions.reduceOrNull { left, right -> left and right } ?: Op.TRUE

    /**
     * Keyset predicate for cursor pagination over a timestamp sort column:
     * `(sortCol, id) > (cursor.sortValue, cursor.lastId)` (mirrored for desc).
     */
    protected fun timestampKeyset(
        cursor: Cursor?,
        order: SortDirection,
        sortColumn: Column<OffsetDateTime>,
        idExpression: Expression<*>,
    ): Op<Boolean>? {
        if (cursor == null) return null
        val sortValue =
            runCatching {
                Instant.parse(cursor.sortValue).atOffset(ZoneOffset.UTC)
            }.getOrElse { throw invalidCursor() }
        return keyset(order, sortColumn, LiteralOp(sortColumn.columnType, sortValue), idExpression, cursor.lastId)
    }

    /** Keyset predicate for cursor pagination over a date sort column or expression. */
    protected fun dateKeyset(
        cursor: Cursor?,
        order: SortDirection,
        sortExpression: ExpressionWithColumnType<LocalDate>,
        idExpression: Expression<*>,
    ): Op<Boolean>? {
        if (cursor == null) return null
        val sortValue =
            runCatching { LocalDate.parse(cursor.sortValue) }
                .getOrElse { throw invalidCursor() }
        return keyset(order, sortExpression, LiteralOp(sortExpression.columnType, sortValue), idExpression, cursor.lastId)
    }

    private fun keyset(
        order: SortDirection,
        sortExpression: Expression<*>,
        sortValue: Expression<*>,
        idExpression: Expression<*>,
        lastId: Long,
    ): Op<Boolean> {
        val idValue = longParam(lastId)
        return if (order == SortDirection.Desc) {
            LessOp(sortExpression, sortValue) or
                (EqOp(sortExpression, sortValue) and LessOp(idExpression, idValue))
        } else {
            GreaterOp(sortExpression, sortValue) or
                (EqOp(sortExpression, sortValue) and GreaterOp(idExpression, idValue))
        }
    }

    private fun invalidCursor(): RequestValidationException = RequestValidationException(field = "cursor", code = ValidationIssueCodes.InvalidFormat, message = "is not a valid cursor")

    protected fun ReadFilters.sortOrder(): SortOrder =
        when (order) {
            SortDirection.Asc -> SortOrder.ASC
            SortDirection.Desc -> SortOrder.DESC
        }
}

sealed interface TimeFilterMode {
    data object StartAtInRange : TimeFilterMode

    data object BeforeFrom : TimeFilterMode

    /** The row's span up to [toColumn] overlaps the window; [inclusiveFrom] also keeps spans ending exactly at `from`. */
    data class OverlapsWindow(
        val toColumn: Column<OffsetDateTime>,
        val inclusiveFrom: Boolean = false,
    ) : TimeFilterMode
}
