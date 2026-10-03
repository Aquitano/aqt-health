package me.aquitano.health.test

import me.aquitano.health.infrastructure.config.DatabaseConfig
import me.aquitano.health.infrastructure.database.DatabaseFactory
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.After
import org.junit.experimental.categories.Category

interface PostgresIntegration

/** Owns database pools until the current test finishes. Ktor applications own their own pools. */
@Category(PostgresIntegration::class)
abstract class PostgresIntegrationTest {
    private val factories = mutableListOf<DatabaseFactory>()

    protected fun openDatabase(config: DatabaseConfig = PostgresTestDatabase.config()): Database {
        val factory = DatabaseFactory()
        factories += factory
        return factory.initialize(config)
    }

    @After
    fun closeDatabasePools() {
        factories.asReversed().forEach { it.close() }
        factories.clear()
    }
}
