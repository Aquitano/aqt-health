package me.aquitano.health.infrastructure.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import me.aquitano.health.infrastructure.logging.Slf4jSqlLogger
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import me.aquitano.health.infrastructure.config.DatabaseConfig as AppDatabaseConfig

class DatabaseFactory(
    private val migrator: FlywayMigrator = FlywayMigrator(),
) : AutoCloseable {
    private var dataSource: HikariDataSource? = null
    private var database: Database? = null

    fun initialize(config: AppDatabaseConfig): Database {
        migrator.migrate(config)

        val hikariConfig =
            HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                username = config.user
                password = config.password
                maximumPoolSize = config.maxPoolSize
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
            }
        close()
        val newDataSource = HikariDataSource(hikariConfig)
        dataSource = newDataSource

        val dbConfig =
            DatabaseConfig {
                sqlLogger = Slf4jSqlLogger
            }
        return Database.connect(newDataSource, databaseConfig = dbConfig).also {
            database = it
            DatabaseDispatchers.register(it, config.maxPoolSize)
        }
    }

    override fun close() {
        database?.let(DatabaseDispatchers::unregister)
        database = null
        dataSource?.close()
        dataSource = null
    }
}
