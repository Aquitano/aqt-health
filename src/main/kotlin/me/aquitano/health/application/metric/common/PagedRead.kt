package me.aquitano.health.application.metric.common

import me.aquitano.health.api.dto.ReadResponseMeta
import me.aquitano.health.application.metric.common.repository.ReadFilters
import me.aquitano.health.infrastructure.database.suspendDbTransaction
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * The shared body of every keyset-paginated list read: [fetch] runs in its own transaction and
 * returns up to `limit + 1` items, the lookahead item becomes `meta.nextCursor` unless the read
 * is `latest=true`, which has no next page.
 */
internal suspend fun <T, R> pagedRead(
    database: Database,
    filters: ReadFilters,
    sortField: String,
    sortValue: (T) -> String,
    id: (T) -> Long,
    respond: (items: List<T>, meta: ReadResponseMeta) -> R,
    fetch: () -> List<T>,
): R {
    val page =
        suspendDbTransaction(db = database) {
            fetch().keysetPage(filters.limit, filters.order, sortValue, id)
        }
    return respond(
        page.items,
        ReadResponseMeta(
            count = page.items.size,
            limit = filters.limit,
            sort = sortField,
            order = filters.order,
            nextCursor = page.nextCursor.takeUnless { filters.latest },
        ),
    )
}
