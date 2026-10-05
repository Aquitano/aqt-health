package me.aquitano.health.application.metric.common

import me.aquitano.health.api.dto.*
import me.aquitano.health.api.dto.SourceMetadataResponse
import me.aquitano.health.application.metric.sleep.repository.SleepNightRow
import me.aquitano.health.application.metric.sleep.repository.SleepSessionRow
import me.aquitano.health.application.metric.sleep.repository.SleepStageRow
import me.aquitano.health.shared.Cursor
import me.aquitano.health.shared.SortDirection

/**
 * Source attribution for an aggregate: reported only when every contributing row came from the
 * same source instance, so a merged multi-provider result is left unattributed.
 */
internal fun <T> Iterable<T>.singleSource(
    sourceMetadata: Map<Int, SourceMetadataResponse>,
    sourceInstanceId: (T) -> Int,
): SourceMetadataResponse? {
    val ids = mapTo(linkedSetOf(), sourceInstanceId)
    if (ids.size != 1) return null
    return sourceMetadata[ids.single()]
}

internal fun SleepSessionRow.toResponse(
    stagesBySession: Map<Int, List<SleepStageRow>>,
    sourceMetadata: Map<Int, SourceMetadataResponse>,
): SleepSessionResponse =
    SleepSessionResponse(
        id = id,
        startAt = startAt.toString(),
        endAt = endAt.toString(),
        durationSeconds = durationSeconds,
        stages =
            stagesBySession[id].orEmpty().map {
                SleepStageResponse(
                    stage = it.stage,
                    startAt = it.startAt.toString(),
                    endAt = it.endAt.toString(),
                    durationSeconds = it.durationSeconds,
                )
            },
        source = sourceMetadata[sourceInstanceId],
    )

internal fun SleepNightRow.toResponse(
    stagesBySession: Map<Int, List<SleepStageRow>>,
    sourceMetadata: Map<Int, SourceMetadataResponse>,
): SleepNightResponse =
    SleepNightResponse(
        date = date,
        timezone = timezone,
        session = session.toResponse(stagesBySession, sourceMetadata),
    )

/**
 * One page of a keyset-paginated list. Repositories fetch limit+1 rows; the extra row only
 * signals that a next page exists and is dropped from [items].
 */
internal data class KeysetPage<T>(
    val items: List<T>,
    val nextCursor: String?,
)

internal fun keysetFetchLimit(limit: Int): Int = if (limit == Int.MAX_VALUE) Int.MAX_VALUE else limit + 1

internal fun <T> List<T>.keysetPage(
    limit: Int,
    order: SortDirection,
    sortValue: (T) -> String,
    id: (T) -> Long,
): KeysetPage<T> =
    if (size > limit) {
        val items = take(limit)
        val last = items.last()
        KeysetPage(items, Cursor(sortValue(last), id(last), order).encode())
    } else {
        KeysetPage(this, null)
    }
