package me.aquitano.health.application

import me.aquitano.health.api.dto.ScalarSample
import me.aquitano.health.domain.BodySegments
import me.aquitano.health.domain.ScalarMetricRegistry
import me.aquitano.health.domain.ScalarSampleRecord
import me.aquitano.health.domain.ScalarValue
import me.aquitano.health.domain.ValidationIssue
import me.aquitano.health.domain.ValidationIssueCodes

/**
 * Maps the single wire record for point-in-time scalar metrics. Ranges, units, contexts,
 * and segment support all come from the [ScalarMetricRegistry] descriptor.
 */
internal fun mapScalarSample(
    field: String,
    dto: ScalarSample,
    issues: MutableList<ValidationIssue>,
): ScalarSampleRecord? {
    val measuredAt = parseInstant(dto.measuredAt, "$field.measuredAt", issues)
    val descriptor = ScalarMetricRegistry.find(dto.metricType)
    if (descriptor == null) {
        issues.add(
            ValidationIssue(
                field = "$field.metricType",
                code = ValidationIssueCodes.UnsupportedValue,
                message = "unsupported scalar metric type",
            )
        )
        return null
    }
    if (dto.unit != null && dto.unit != descriptor.unit) {
        issues.add(
            ValidationIssue(
                field = "$field.unit",
                code = ValidationIssueCodes.UnsupportedValue,
                message = "must be ${descriptor.unit}",
            )
        )
    }
    if (!descriptor.valueIsValid(dto.value)) {
        issues.add(
            ValidationIssue(
                field = "$field.value",
                code = ValidationIssueCodes.OutOfRange,
                message = "out of range for ${descriptor.metricType}",
            )
        )
    }
    val allowedContexts = descriptor.allowedContexts
    val context = dto.context ?: if (allowedContexts != null) "unknown" else null
    if (allowedContexts != null && context !in allowedContexts) {
        issues.add(
            ValidationIssue(
                field = "$field.context",
                code = ValidationIssueCodes.UnsupportedValue,
                message = "unsupported context for ${descriptor.metricType}",
            )
        )
    }
    if (allowedContexts == null && dto.context != null) {
        issues.add(
            ValidationIssue(
                field = "$field.context",
                code = ValidationIssueCodes.UnsupportedValue,
                message = "${descriptor.metricType} does not support a context",
            )
        )
    }
    if (dto.segment != null && !descriptor.supportsSegment) {
        issues.add(
            ValidationIssue(
                field = "$field.segment",
                code = ValidationIssueCodes.UnsupportedValue,
                message = "${descriptor.metricType} does not support a segment",
            )
        )
    }
    if (dto.segment != null && dto.segment !in BodySegments.supported) {
        issues.add(
            ValidationIssue(
                field = "$field.segment",
                code = ValidationIssueCodes.UnsupportedValue,
                message = "unsupported body segment",
            )
        )
    }

    val valid = measuredAt != null &&
        (dto.unit == null || dto.unit == descriptor.unit) &&
        descriptor.valueIsValid(dto.value) &&
        (allowedContexts == null && dto.context == null || allowedContexts != null && context in allowedContexts) &&
        (dto.segment == null || descriptor.supportsSegment && dto.segment in BodySegments.supported)
    return if (valid) {
        ScalarSampleRecord(
            providerRecordId = dto.providerRecordId,
            normalizedRecordJson = dto.toNormalizedJsonObject(),
            measuredAt = measuredAt,
            values = listOf(
                ScalarValue(
                    metricType = descriptor.metricType,
                    value = dto.value,
                    context = context,
                    segment = dto.segment,
                )
            ),
        )
    } else {
        null
    }
}
