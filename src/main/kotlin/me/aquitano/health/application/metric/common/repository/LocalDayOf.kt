package me.aquitano.health.application.metric.common.repository

import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Function
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.javatime.JavaLocalDateColumnType
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Truncates a `timestamptz` to the calendar day of [zoneId]. Postgres `date_trunc` runs in the
 * session timezone, so the explicit `AT TIME ZONE` conversion is what keeps a timestamp on either
 * side of local midnight in the correct day rather than silently misbucketing on the session's zone.
 *
 * The zone is inlined as a literal (not a bound parameter) so the identical expression renders in
 * every clause that needs it; a parameter would emit distinct placeholders that Postgres refuses to
 * treat as the same grouping key. [zoneId] is a validated IANA identifier from `ZoneId.of`.
 */
internal class LocalDayOf(
    private val timestamp: Expression<OffsetDateTime>,
    private val zoneId: String,
) : Function<LocalDate>(JavaLocalDateColumnType()) {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append("CAST(date_trunc('day', ")
        append(timestamp)
        append(" AT TIME ZONE ")
        append(stringLiteral(zoneId))
        append(") AS DATE)")
    }
}
