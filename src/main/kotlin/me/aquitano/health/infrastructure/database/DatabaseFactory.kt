package me.aquitano.health.infrastructure.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import me.aquitano.health.infrastructure.config.DatabaseConfig
import org.jetbrains.exposed.sql.Database
import java.util.Collections

class DatabaseFactory(
    private val migrator: FlywayMigrator = FlywayMigrator(),
) : AutoCloseable {
    private var dataSource: HikariDataSource? = null

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
        close()
        val newDataSource = HikariDataSource(hikariConfig)
        dataSource = newDataSource
        openDataSources.add(newDataSource)
        return Database.connect(newDataSource)
    }

    override fun close() {
        dataSource?.let {
            it.close()
            openDataSources.remove(it)
        }
        dataSource = null
    }

    companion object {
        private val openDataSources =
            Collections.synchronizedSet(mutableSetOf<HikariDataSource>())

        init {
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    openDataSources.toList().forEach { it.close() }
                    openDataSources.clear()
                }
            )
        }
    }
}
