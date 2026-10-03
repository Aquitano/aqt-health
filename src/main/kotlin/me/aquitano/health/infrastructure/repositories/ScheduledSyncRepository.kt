package me.aquitano.health.infrastructure.repositories

import me.aquitano.health.infrastructure.database.tables.ProviderScheduledSyncCheckpointsTable
import me.aquitano.health.infrastructure.database.tables.ProviderScheduledSyncConfigsTable
import me.aquitano.health.infrastructure.database.toDbTimestamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import me.aquitano.health.infrastructure.database.suspendDbTransaction
import java.time.Instant

data class ScheduledSyncConfigRecord(
    val id: Int,
    val providerCode: String,
    val providerInstanceId: String,
    val enabled: Boolean,
    val dataTypes: List<String>,
    val cadenceMinutes: Int,
    val lookbackDays: Int,
    val lastSuccessfulFrom: Instant?,
    val lastSuccessfulTo: Instant?,
    val lastSuccessAt: Instant?,
    val lastAttemptedAt: Instant?,
    val failureCount: Int,
    val nextRunAt: Instant?,
    val lastErrorMessage: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ScheduledSyncCheckpointRecord(
    val id: Int,
    val configId: Int,
    val dataType: String,
    val checkpointAt: Instant?,
    val lastSuccessfulFrom: Instant?,
    val lastSuccessfulTo: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

class ScheduledSyncRepository(private val database: Database) {
    suspend fun getConfig(
        providerCode: String,
        providerInstanceId: String,
    ): ScheduledSyncConfigRecord? =
        suspendDbTransaction(db = database) { configBy(providerCode, providerInstanceId) }

    suspend fun upsertConfig(
        providerCode: String,
        providerInstanceId: String,
        enabled: Boolean,
        dataTypes: List<String>,
        cadenceMinutes: Int,
        lookbackDays: Int,
        nextRunAt: Instant?,
        now: Instant,
    ): ScheduledSyncConfigRecord =
        suspendDbTransaction(db = database) {
            val nowTimestamp = now.toDbTimestamp()
            // Run history (last success/attempt, failure count, last error) belongs to the
            // scheduler, not to a config edit, so an update leaves those columns alone.
            ProviderScheduledSyncConfigsTable.upsert(
                ProviderScheduledSyncConfigsTable.providerCode,
                ProviderScheduledSyncConfigsTable.providerInstanceId,
                onUpdateExclude = listOf(
                    ProviderScheduledSyncConfigsTable.lastSuccessfulFrom,
                    ProviderScheduledSyncConfigsTable.lastSuccessfulTo,
                    ProviderScheduledSyncConfigsTable.lastSuccessAt,
                    ProviderScheduledSyncConfigsTable.lastAttemptedAt,
                    ProviderScheduledSyncConfigsTable.failureCount,
                    ProviderScheduledSyncConfigsTable.lastErrorMessage,
                    ProviderScheduledSyncConfigsTable.createdAt,
                ),
            ) {
                it[this.providerCode] = providerCode
                it[this.providerInstanceId] = providerInstanceId
                it[this.enabled] = enabled
                it[this.dataTypes] = encodeDataTypes(dataTypes)
                it[this.cadenceMinutes] = cadenceMinutes
                it[this.lookbackDays] = lookbackDays
                it[lastSuccessfulFrom] = null
                it[lastSuccessfulTo] = null
                it[lastSuccessAt] = null
                it[lastAttemptedAt] = null
                it[failureCount] = 0
                it[this.nextRunAt] = nextRunAt?.toDbTimestamp()
                it[lastErrorMessage] = null
                it[createdAt] = nowTimestamp
                it[updatedAt] = nowTimestamp
            }
            val config = configBy(providerCode, providerInstanceId)
                ?: error("Scheduled sync config was not persisted")
            syncCheckpointRows(config.id, dataTypes, now)
            config
        }

    suspend fun dueConfigs(
        now: Instant,
        limit: Int = 10,
    ): List<ScheduledSyncConfigRecord> =
        suspendDbTransaction(db = database) {
            ProviderScheduledSyncConfigsTable
                .selectAll()
                .where {
                    (ProviderScheduledSyncConfigsTable.enabled eq true) and
                            (ProviderScheduledSyncConfigsTable.nextRunAt lessEq now.toDbTimestamp())
                }
                .orderBy(ProviderScheduledSyncConfigsTable.nextRunAt to SortOrder.ASC)
                .limit(limit)
                .map { it.toConfig() }
        }

    suspend fun checkpoints(configId: Int): List<ScheduledSyncCheckpointRecord> =
        suspendDbTransaction(db = database) {
            ProviderScheduledSyncCheckpointsTable
                .selectAll()
                .where { ProviderScheduledSyncCheckpointsTable.configId eq configId }
                .orderBy(ProviderScheduledSyncCheckpointsTable.dataType to SortOrder.ASC)
                .map { it.toCheckpoint() }
        }

    suspend fun markAttempt(configId: Int, attemptedAt: Instant) {
        suspendDbTransaction(db = database) {
            ProviderScheduledSyncConfigsTable.update({ ProviderScheduledSyncConfigsTable.id eq configId }) {
                it[lastAttemptedAt] = attemptedAt.toDbTimestamp()
                it[updatedAt] = attemptedAt.toDbTimestamp()
            }
        }
    }

    suspend fun markDataTypeSuccess(
        configId: Int,
        dataType: String,
        from: Instant,
        to: Instant,
        now: Instant,
    ) {
        suspendDbTransaction(db = database) {
            val nowTimestamp = now.toDbTimestamp()
            ProviderScheduledSyncCheckpointsTable.upsert(
                ProviderScheduledSyncCheckpointsTable.configId,
                ProviderScheduledSyncCheckpointsTable.dataType,
                onUpdateExclude = listOf(ProviderScheduledSyncCheckpointsTable.createdAt),
            ) {
                it[this.configId] = configId
                it[this.dataType] = dataType
                it[checkpointAt] = to.toDbTimestamp()
                it[lastSuccessfulFrom] = from.toDbTimestamp()
                it[lastSuccessfulTo] = to.toDbTimestamp()
                it[createdAt] = nowTimestamp
                it[updatedAt] = nowTimestamp
            }
        }
    }

    suspend fun markSuccess(
        configId: Int,
        from: Instant,
        to: Instant,
        nextRunAt: Instant,
        now: Instant,
    ) {
        suspendDbTransaction(db = database) {
            ProviderScheduledSyncConfigsTable.update({ ProviderScheduledSyncConfigsTable.id eq configId }) {
                it[lastSuccessfulFrom] = from.toDbTimestamp()
                it[lastSuccessfulTo] = to.toDbTimestamp()
                it[lastSuccessAt] = now.toDbTimestamp()
                it[failureCount] = 0
                it[this.nextRunAt] = nextRunAt.toDbTimestamp()
                it[lastErrorMessage] = null
                it[updatedAt] = now.toDbTimestamp()
            }
        }
    }

    suspend fun markFailure(
        configId: Int,
        failureCount: Int,
        nextRunAt: Instant?,
        errorMessage: String,
        now: Instant,
    ) {
        suspendDbTransaction(db = database) {
            ProviderScheduledSyncConfigsTable.update({ ProviderScheduledSyncConfigsTable.id eq configId }) {
                it[this.failureCount] = failureCount
                it[this.nextRunAt] = nextRunAt?.toDbTimestamp()
                it[lastErrorMessage] = errorMessage.take(2000)
                it[updatedAt] = now.toDbTimestamp()
            }
        }
    }

    private fun syncCheckpointRows(
        configId: Int,
        dataTypes: List<String>,
        now: Instant,
    ) {
        val nowTimestamp = now.toDbTimestamp()
        ProviderScheduledSyncCheckpointsTable.deleteWhere {
            (ProviderScheduledSyncCheckpointsTable.configId eq configId) and
                    (ProviderScheduledSyncCheckpointsTable.dataType notInList dataTypes)
        }
        // Newly selected data types start without a checkpoint; existing ones keep theirs.
        dataTypes.forEach { dataType ->
            ProviderScheduledSyncCheckpointsTable.insertIgnore {
                it[this.configId] = configId
                it[this.dataType] = dataType
                it[checkpointAt] = null
                it[lastSuccessfulFrom] = null
                it[lastSuccessfulTo] = null
                it[createdAt] = nowTimestamp
                it[updatedAt] = nowTimestamp
            }
        }
    }

    private fun configBy(
        providerCode: String,
        providerInstanceId: String,
    ): ScheduledSyncConfigRecord? =
        ProviderScheduledSyncConfigsTable
            .selectAll()
            .where {
                (ProviderScheduledSyncConfigsTable.providerCode eq providerCode) and
                        (ProviderScheduledSyncConfigsTable.providerInstanceId eq providerInstanceId)
            }
            .limit(1)
            .map { it.toConfig() }
            .singleOrNull()

    private fun ResultRow.toConfig(): ScheduledSyncConfigRecord =
        ScheduledSyncConfigRecord(
            id = this[ProviderScheduledSyncConfigsTable.id].value,
            providerCode = this[ProviderScheduledSyncConfigsTable.providerCode],
            providerInstanceId = this[ProviderScheduledSyncConfigsTable.providerInstanceId],
            enabled = this[ProviderScheduledSyncConfigsTable.enabled],
            dataTypes = decodeDataTypes(this[ProviderScheduledSyncConfigsTable.dataTypes]),
            cadenceMinutes = this[ProviderScheduledSyncConfigsTable.cadenceMinutes],
            lookbackDays = this[ProviderScheduledSyncConfigsTable.lookbackDays],
            lastSuccessfulFrom = this[ProviderScheduledSyncConfigsTable.lastSuccessfulFrom]?.toInstant(),
            lastSuccessfulTo = this[ProviderScheduledSyncConfigsTable.lastSuccessfulTo]?.toInstant(),
            lastSuccessAt = this[ProviderScheduledSyncConfigsTable.lastSuccessAt]?.toInstant(),
            lastAttemptedAt = this[ProviderScheduledSyncConfigsTable.lastAttemptedAt]?.toInstant(),
            failureCount = this[ProviderScheduledSyncConfigsTable.failureCount],
            nextRunAt = this[ProviderScheduledSyncConfigsTable.nextRunAt]?.toInstant(),
            lastErrorMessage = this[ProviderScheduledSyncConfigsTable.lastErrorMessage],
            createdAt = this[ProviderScheduledSyncConfigsTable.createdAt].toInstant(),
            updatedAt = this[ProviderScheduledSyncConfigsTable.updatedAt].toInstant(),
        )

    private fun ResultRow.toCheckpoint(): ScheduledSyncCheckpointRecord =
        ScheduledSyncCheckpointRecord(
            id = this[ProviderScheduledSyncCheckpointsTable.id].value,
            configId = this[ProviderScheduledSyncCheckpointsTable.configId].value,
            dataType = this[ProviderScheduledSyncCheckpointsTable.dataType],
            checkpointAt = this[ProviderScheduledSyncCheckpointsTable.checkpointAt]?.toInstant(),
            lastSuccessfulFrom = this[ProviderScheduledSyncCheckpointsTable.lastSuccessfulFrom]?.toInstant(),
            lastSuccessfulTo = this[ProviderScheduledSyncCheckpointsTable.lastSuccessfulTo]?.toInstant(),
            createdAt = this[ProviderScheduledSyncCheckpointsTable.createdAt].toInstant(),
            updatedAt = this[ProviderScheduledSyncCheckpointsTable.updatedAt].toInstant(),
        )
}
