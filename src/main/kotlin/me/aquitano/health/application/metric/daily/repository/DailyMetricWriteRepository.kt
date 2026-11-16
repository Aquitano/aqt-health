package me.aquitano.health.application.metric.daily.repository

import me.aquitano.health.domain.DailyMetricRecord
import me.aquitano.health.infrastructure.database.tables.DailyMetricValuesTable
import me.aquitano.health.infrastructure.database.toDbTimestamp
import org.jetbrains.exposed.v1.jdbc.insertIgnoreAndGetId
import java.time.Instant

class DailyMetricWriteRepository {
    fun insertDailyMetric(
        sourceInstanceId: Int,
        ingestionRecordId: Int,
        record: DailyMetricRecord,
        now: Instant,
    ): Boolean =
        DailyMetricValuesTable.insertIgnoreAndGetId {
            it[this.sourceInstanceId] = sourceInstanceId
            it[this.ingestionRecordId] = ingestionRecordId
            it[providerRecordId] = record.providerRecordId
            it[date] = record.date
            it[metricType] = record.metricType
            it[value] = record.value
            it[createdAt] = now.toDbTimestamp()
        } != null
}
