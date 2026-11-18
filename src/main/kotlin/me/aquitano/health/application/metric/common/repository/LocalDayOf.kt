package me.aquitano.health.application.metric.common.repository

import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Function
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.javatime.JavaLocalDateColumnType
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Truncates a `timestamptz` to the calendar day of [zone]. Postgres `date_trunc` runs in the
 * session timezone, so the explicit `AT TIME ZONE` conversion is what keeps a timestamp on either
 * side of local midnight in the correct day rather than silently misbucketing on the session's zone.
 *
 * The zone is inlined as a literal (not a bound parameter) so the identical expression renders in
 * every clause that needs it; a parameter would emit distinct placeholders that Postgres refuses to
 * treat as the same grouping key. A fixed offset is inlined as an interval, because Postgres reads
 * text such as '+02:00' as a POSIX zone, where a positive offset lies west of Greenwich.
 */
internal class LocalDayOf(
    private val timestamp: Expression<OffsetDateTime>,
    private val zone: ZoneId,
) : Function<LocalDate>(JavaLocalDateColumnType()) {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) =
        queryBuilder {
            append("CAST(date_trunc('day', ")
            append(timestamp)
            append(" AT TIME ZONE ")
            when (val fixed = zone.normalized()) {
                is ZoneOffset -> append("INTERVAL '${fixed.totalSeconds} seconds'")
                else -> append(stringLiteral(zone.id))
            }
            append(") AS DATE)")
        }
}
