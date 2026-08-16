package me.aquitano.health.application

import me.aquitano.health.api.dto.SourceMetadataResponse
import me.aquitano.health.application.metric.common.repository.SourceMetadata

internal fun <T> List<T>.singleSource(
    includeSource: Boolean,
    sourceMetadata: Map<Int, SourceMetadata>,
    sourceInstanceId: (T) -> Int,
): SourceMetadataResponse? {
    if (!includeSource) return null
    val ids = mapTo(linkedSetOf(), sourceInstanceId)
    if (ids.size != 1) return null
    return sourceMetadata[ids.single()].toResponse()
}

private fun SourceMetadata?.toResponse(): SourceMetadataResponse? =
    this?.let {
        SourceMetadataResponse(
            provider = it.provider,
            providerInstanceId = it.providerInstanceId,
        )
    }
