package me.aquitano.health.infrastructure.database.dao

import me.aquitano.health.infrastructure.database.tables.ApiClientsTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

class ApiClientDao(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ApiClientDao>(ApiClientsTable)

    var name by ApiClientsTable.name
    var apiKeyHash by ApiClientsTable.apiKeyHash
    var enabled by ApiClientsTable.enabled
    var createdAt by ApiClientsTable.createdAt
    var lastUsedAt by ApiClientsTable.lastUsedAt
}
