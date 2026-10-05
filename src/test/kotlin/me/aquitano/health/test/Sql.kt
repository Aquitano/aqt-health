package me.aquitano.health.test

import me.aquitano.health.infrastructure.config.DatabaseConfig
import java.sql.ResultSet

fun DatabaseConfig.queryInt(sql: String): Int = querySingle(sql) { it.getInt(1) }

fun DatabaseConfig.queryString(sql: String): String? = querySingle(sql) { it.getString(1) }

fun DatabaseConfig.countRows(table: String): Int = queryInt("SELECT COUNT(*) FROM $table")

fun DatabaseConfig.execute(sql: String) {
    PostgresTestDatabase.connection(this).use { connection ->
        connection.createStatement().use { it.execute(sql) }
    }
}

private fun <T> DatabaseConfig.querySingle(
    sql: String,
    read: (ResultSet) -> T,
): T =
    PostgresTestDatabase.connection(this).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                check(resultSet.next()) { "No row for: $sql" }
                read(resultSet)
            }
        }
    }
