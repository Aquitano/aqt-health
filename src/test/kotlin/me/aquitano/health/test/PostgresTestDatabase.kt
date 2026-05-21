package me.aquitano.health.test

import me.aquitano.health.infrastructure.config.DatabaseConfig
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

object PostgresTestDatabase {
    private val container: PostgreSQLContainer<Nothing> by lazy {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
            withDatabaseName("postgres")
            withUsername("aqt_health")
            withPassword("aqt_health")
            start()
        }
    }

    fun config(): DatabaseConfig {
        externalConfig()?.let { return it }

        val databaseName = "aqt_health_test_${UUID.randomUUID().toString().replace("-", "")}"
        adminConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("""CREATE DATABASE "$databaseName"""")
            }
        }
        return DatabaseConfig(
            jdbcUrl = "jdbc:postgresql://${container.host}:${container.getMappedPort(5432)}/$databaseName",
            driver = "org.postgresql.Driver",
            user = container.username,
            password = container.password,
            maxPoolSize = 4,
        )
    }

    fun connection(config: DatabaseConfig): Connection =
        DriverManager.getConnection(config.jdbcUrl, config.user, config.password)

    fun ktorConfigEntries(config: DatabaseConfig): Array<Pair<String, String>> =
        arrayOf(
            "aqtHealth.database.jdbcUrl" to config.jdbcUrl,
            "aqtHealth.database.driver" to config.driver,
            "aqtHealth.database.user" to config.user,
            "aqtHealth.database.password" to config.password,
            "aqtHealth.database.maxPoolSize" to config.maxPoolSize.toString(),
        )

    private fun adminConnection(): Connection =
        DriverManager.getConnection(
            "jdbc:postgresql://${container.host}:${container.getMappedPort(5432)}/postgres",
            container.username,
            container.password,
        )

    private fun externalConfig(): DatabaseConfig? {
        val jdbcUrl = System.getenv("AQT_HEALTH_TEST_JDBC_URL") ?: return null
        val user = System.getenv("AQT_HEALTH_TEST_DB_USER") ?: "aqt_health"
        val password = System.getenv("AQT_HEALTH_TEST_DB_PASSWORD") ?: "aqt_health"
        val schema = "aqt_health_test_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(jdbcUrl, user, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("""CREATE SCHEMA "$schema"""")
            }
        }
        return DatabaseConfig(
            jdbcUrl = jdbcUrl.withJdbcParameter("currentSchema", schema),
            driver = "org.postgresql.Driver",
            user = user,
            password = password,
            maxPoolSize = 4,
        )
    }

    private fun String.withJdbcParameter(name: String, value: String): String {
        val separator = if (contains("?")) "&" else "?"
        return "$this$separator$name=$value"
    }
}
