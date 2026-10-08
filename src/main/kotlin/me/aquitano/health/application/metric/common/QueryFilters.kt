package me.aquitano.health.application.metric.common

import me.aquitano.health.application.metric.common.repository.ReadFilters
import me.aquitano.health.domain.RequestValidationException
import me.aquitano.health.domain.ValidationIssue
import me.aquitano.health.domain.ValidationIssueCodes
import me.aquitano.health.shared.SortDirection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

internal fun QueryParams.readFilters(): ReadFilters {
    val latest = boolean(QueryParamSpecs.latest)
    if (latest) {
        rejectLatestOverrides()
    }
    val from = instant("from")
    val to = instant("to")
    validateRange(from, to, "from", "to")
    val order = if (latest) SortDirection.Desc else order()
    return ReadFilters(
        from = from,
        to = to,
        provider = optional("provider"),
        providerInstanceId = optional("providerInstanceId"),
        includeSource = boolean(QueryParamSpecs.includeSource),
        limit = if (latest) 1 else int(QueryParamSpecs.readLimit),
        order = order,
        cursor = if (latest) null else cursor(order),
        latest = latest,
    )
}

internal fun QueryParams.summaryFilters(): ReadFilters {
    rejectLatest()
    val from = instant("from")
    val to = instant("to")
    validateRange(from, to, "from", "to")
    return ReadFilters(
        from = from,
        to = to,
        provider = optional("provider"),
        providerInstanceId = optional("providerInstanceId"),
        includeSource = boolean("includeSource", default = false),
        limit = 1,
        order = SortDirection.Desc,
    )
}

internal fun QueryParams.dailyReadFilters(
    now: Instant,
    timezone: ZoneId = ZoneOffset.UTC,
): ReadFilters {
    val (fromDate, toDate) = dailyDateRange(now, timezone)
    val order = order()
    return ReadFilters(
        fromDate = fromDate,
        toDate = toDate,
        timezone = timezone,
        provider = optional("provider"),
        providerInstanceId = optional("providerInstanceId"),
        includeSource = boolean(QueryParamSpecs.includeSource),
        limit = if (optional("date") != null) 1 else int(QueryParamSpecs.readLimit),
        order = order,
        cursor = cursor(order),
    )
}

internal fun QueryParams.dailyLatestReadFilters(now: Instant): ReadFilters {
    rejectLatestOverrides(message = "is not supported for latest endpoints")
    val (fromDate, toDate) = dailyDateRange(now, ZoneOffset.UTC)
    return ReadFilters(
        fromDate = fromDate,
        toDate = toDate,
        provider = optional("provider"),
        providerInstanceId = optional("providerInstanceId"),
        includeSource = boolean("includeSource", default = false),
        limit = 1,
        order = SortDirection.Desc,
        latest = true,
    )
}

private fun QueryParams.dailyDateRange(
    now: Instant,
    timezone: ZoneId,
): Pair<LocalDate?, LocalDate?> {
    val exactDate = dateOrToday("date", now, timezone)
    if (exactDate != null && (optional("fromDate") != null || optional("toDate") != null)) {
        throw RequestValidationException(field = "date", code = ValidationIssueCodes.InvalidState, message = "cannot be combined with fromDate or toDate")
    }
    val fromDate = exactDate ?: date("fromDate")
    val toDate = exactDate ?: date("toDate")
    validateDateRange(fromDate, toDate)
    return fromDate to toDate
}

internal fun validateRange(
    from: Instant?,
    to: Instant?,
    fromField: String,
    toField: String,
) {
    if (from != null && to != null && !from.isBefore(to)) {
        throw RequestValidationException(field = fromField, code = ValidationIssueCodes.InvalidRange, message = "must be before $toField")
    }
}

internal fun validateDateRange(
    fromDate: LocalDate?,
    toDate: LocalDate?,
) {
    if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
        throw RequestValidationException(field = "fromDate", code = ValidationIssueCodes.InvalidRange, message = "must be on or before toDate")
    }
}

internal object SortFields {
    const val START_AT = "startAt"
    const val END_AT = "endAt"
    const val DATE = "date"
    const val MEASURED_AT = "measuredAt"
}
