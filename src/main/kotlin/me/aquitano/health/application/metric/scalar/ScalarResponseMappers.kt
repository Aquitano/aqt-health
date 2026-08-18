package me.aquitano.health.application.metric.scalar

import me.aquitano.health.api.dto.ScalarSampleResponse
import me.aquitano.health.application.metric.common.repository.SourceMetadata
import me.aquitano.health.application.metric.common.toResponse

internal fun ScalarSampleRow.toScalarResponse(
    sourceMetadata: Map<Int, SourceMetadata>,
): ScalarSampleResponse =
    ScalarSampleResponse(
        id = id,
        measuredAt = measuredAt.toString(),
        metricType = metricType,
        value = value,
        unit = unit,
        context = context,
        segment = segment,
        source = sourceMetadata[sourceInstanceId].toResponse(),
    )
