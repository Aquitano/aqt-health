package me.aquitano.health.application

import me.aquitano.health.api.dto.DailyMetric
import me.aquitano.health.domain.DailyMetricRecord
import me.aquitano.health.domain.DailyMetricRegistry
import me.aquitano.health.domain.ValidationIssue
import me.aquitano.health.domain.ValidationIssueCodes
import me.aquitano.health.domain.roundToScalarPrecision

internal fun mapDailyMetric(
    field: String,
    dto: DailyMetric,
    issues: MutableList<ValidationIssue>,
): DailyMetricRecord? {
    val date = parseDate(dto.date, "$field.date", issues)
    val descriptor = DailyMetricRegistry.find(dto.metricType)
    if (descriptor == null) {
        issues.add(ValidationIssue(field = "$field.metricType", code = ValidationIssueCodes.UnsupportedValue, message = "unsupported daily metric type"))
        return null
    }
    if (dto.value !in descriptor.valueRange) {
        issues.add(ValidationIssue(field = "$field.value", code = ValidationIssueCodes.OutOfRange, message = "out of range for ${descriptor.metricType}"))
    }
    return date?.let { DailyMetricRecord(dto.providerRecordId, it, descriptor.metricType, dto.value.roundToScalarPrecision()) }
}
