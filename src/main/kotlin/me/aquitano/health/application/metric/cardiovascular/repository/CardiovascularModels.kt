package me.aquitano.health.application.metric.cardiovascular.repository

import java.time.Instant

data class BloodPressureMeasurementRow(
    val id: Int,
    val sourceInstanceId: Int,
    val measuredAt: Instant,
    val systolicMmhg: Int,
    val diastolicMmhg: Int,
    val heartRateBpm: Int?,
)

