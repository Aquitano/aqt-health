package me.aquitano.health.application.metric.scalar

import me.aquitano.health.api.dto.ScalarSampleResponse
import me.aquitano.health.api.dto.SourceMetadataResponse

internal fun ScalarSampleRow.toScalarResponse(
    sourceMetadata: Map<Int, SourceMetadataResponse>,
): ScalarSampleResponse =
    ScalarSampleResponse(
        id = id,
        measuredAt = measuredAt.toString(),
        metricType = metricType,
        value = value,
        unit = unit,
        context = context,
        segment = segment,
        source = sourceMetadata[sourceInstanceId],
    )
