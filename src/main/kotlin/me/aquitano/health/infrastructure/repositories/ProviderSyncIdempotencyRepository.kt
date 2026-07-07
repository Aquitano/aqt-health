package me.aquitano.health.infrastructure.repositories

import me.aquitano.health.infrastructure.database.suspendDbTransaction
import me.aquitano.health.infrastructure.database.tables.ProviderSyncIdempotencyTable
import me.aquitano.health.infrastructure.database.toDbTimestamp
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant

/** Stores responses of the synchronous provider sync endpoint keyed by Idempotency-Key. */
class ProviderSyncIdempotencyRepository(private val database: Database) {
    suspend fun findResponse(providerCode: String, idempotencyKey: String): String? =
        suspendDbTransaction(db = database) {
            ProviderSyncIdempotencyTable
                .selectAll()
                .where {
                    (ProviderSyncIdempotencyTable.providerCode eq providerCode) and
                        (ProviderSyncIdempotencyTable.idempotencyKey eq idempotencyKey)
                }
                .limit(1)
                .map { it[ProviderSyncIdempotencyTable.responseJson] }
                .singleOrNull()
        }

    suspend fun storeResponse(
        providerCode: String,
        idempotencyKey: String,
        responseJson: String,
        now: Instant,
    ) {
        suspendDbTransaction(db = database) {
            // Concurrent duplicates both execute the sync; the first stored response wins.
            ProviderSyncIdempotencyTable.insertIgnore {
                it[this.providerCode] = providerCode
                it[this.idempotencyKey] = idempotencyKey
                it[this.responseJson] = responseJson
                it[createdAt] = now.toDbTimestamp()
            }
        }
    }
}
