package me.aquitano.health.infrastructure.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import me.aquitano.health.infrastructure.config.DatabaseConfig
import org.jetbrains.exposed.sql.Database

class DatabaseFactory(
    private val migrator: FlywayMigrator = FlywayMigrator(),
) {
    fun initialize(config: DatabaseConfig): Database {
        Class.forName(config.driver)
        migrator.migrate(config)

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.user
            password = config.password
            maximumPoolSize = config.maxPoolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
        }
        return Database.connect(HikariDataSource(hikariConfig))
    }
}
