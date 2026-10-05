package me.aquitano.health.application.metric.common.repository

import me.aquitano.health.shared.Cursor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Timestamp reads use [from]/[to]; date-keyed reads use [fromDate]/[toDate], and sleep nights
 * also label dates in [timezone].
 */
data class ReadFilters(
    val from: Instant? = null,
    val to: Instant? = null,
    val fromDate: LocalDate? = null,
    val toDate: LocalDate? = null,
    val timezone: ZoneId = ZoneOffset.UTC,
    val provider: String?,
    val providerInstanceId: String?,
    val includeSource: Boolean,
    val limit: Int,
    val order: String,
    val cursor: Cursor? = null,
    val latest: Boolean = false,
)
