package me.aquitano.health.infrastructure.repositories

import me.aquitano.health.infrastructure.database.suspendDbTransaction
import me.aquitano.health.infrastructure.database.tables.ApiClientsTable
import me.aquitano.health.infrastructure.database.tables.SourceInstancesTable
import me.aquitano.health.infrastructure.database.tables.SourcesTable
import me.aquitano.health.infrastructure.database.toDbTimestamp
import me.aquitano.health.shared.normalizeProviderCode
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnoreAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.OffsetDateTime

data class SourceInstanceRef(
    val id: Int,
    val provider: String,
    val providerInstanceId: String,
)

data class ApiClientRef(
    val id: Int,
    val name: String,
)

enum class BootstrapApiClientOutcome {
    CREATED,
    ROTATED,
    UNCHANGED,
}

private const val API_CLIENT_LAST_USED_UPDATE_INTERVAL_SECONDS = 60L

class SupportRepository(
    private val database: Database,
) {
    /**
     * Reconciles the bootstrap client with the configured API key: creates it when missing and
     * rewrites the stored hash when the configured key changed, so key rotation takes effect on
     * the next boot instead of leaving the old key valid.
     */
    fun upsertBootstrapApiClient(
        name: String,
        apiKeyHash: String,
        now: Instant,
    ): BootstrapApiClientOutcome =
        transaction(database) {
            val existingHash =
                ApiClientsTable
                    .select(ApiClientsTable.apiKeyHash)
                    .where { ApiClientsTable.name eq name }
                    .singleOrNull()
                    ?.get(ApiClientsTable.apiKeyHash)
            when (existingHash) {
                null -> {
                    ApiClientsTable.insert {
                        it[ApiClientsTable.name] = name
                        it[ApiClientsTable.apiKeyHash] = apiKeyHash
                        it[enabled] = true
                        it[createdAt] = now.toDbTimestamp()
                    }
                    BootstrapApiClientOutcome.CREATED
                }

                apiKeyHash -> {
                    BootstrapApiClientOutcome.UNCHANGED
                }

                else -> {
                    ApiClientsTable.update({ ApiClientsTable.name eq name }) {
                        it[ApiClientsTable.apiKeyHash] = apiKeyHash
                    }
                    BootstrapApiClientOutcome.ROTATED
                }
            }
        }

    suspend fun findEnabledApiClientByHash(
        apiKeyHash: String,
        now: Instant,
    ): ApiClientRef? =
        suspendDbTransaction(db = database) {
            val client =
                ApiClientsTable
                    .select(ApiClientsTable.id, ApiClientsTable.name, ApiClientsTable.lastUsedAt)
                    .where { (ApiClientsTable.apiKeyHash eq apiKeyHash) and (ApiClientsTable.enabled eq true) }
                    .singleOrNull()
                    ?: return@suspendDbTransaction null
            val id = client[ApiClientsTable.id]
            if (shouldUpdateLastUsedAt(client[ApiClientsTable.lastUsedAt], now)) {
                ApiClientsTable.update({ ApiClientsTable.id eq id }) { it[lastUsedAt] = now.toDbTimestamp() }
            }
            ApiClientRef(id = id.value, name = client[ApiClientsTable.name])
        }

    /**
     * [provider] is normalized before it reaches sources.code: a wire-spelled code such as
     * `google-health` would otherwise create a second source that matches no provider_ranks row.
     */
    fun resolveOrCreateSourceInstanceInTransaction(
        provider: String,
        providerInstanceId: String,
        now: Instant,
    ): SourceInstanceRef {
        val providerCode = normalizeProviderCode(provider)
        val sourceId =
            SourcesTable
                .insertIgnoreAndGetId {
                    it[code] = providerCode
                    it[createdAt] = now.toDbTimestamp()
                }?.value ?: SourcesTable
                .select(SourcesTable.id)
                .where { SourcesTable.code eq providerCode }
                .limit(1)
                .single()[SourcesTable.id]
                .value

        val instanceId =
            SourceInstancesTable
                .insertIgnoreAndGetId {
                    it[this.sourceId] = sourceId
                    it[this.providerInstanceId] = providerInstanceId
                    it[createdAt] = now.toDbTimestamp()
                    it[updatedAt] = now.toDbTimestamp()
                }?.value ?: SourceInstancesTable
                .select(SourceInstancesTable.id)
                .where {
                    (SourceInstancesTable.sourceId eq sourceId) and
                        (SourceInstancesTable.providerInstanceId eq providerInstanceId)
                }.limit(1)
                .single()[SourceInstancesTable.id]
                .value

        return SourceInstanceRef(
            id = instanceId,
            provider = provider,
            providerInstanceId = providerInstanceId,
        )
    }

    private fun shouldUpdateLastUsedAt(
        current: OffsetDateTime?,
        now: Instant,
    ): Boolean {
        if (current == null) return true
        return current.toInstant() <=
            now.minusSeconds(
                API_CLIENT_LAST_USED_UPDATE_INTERVAL_SECONDS,
            )
    }
}
