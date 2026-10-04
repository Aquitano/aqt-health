package me.aquitano.health.test

import me.aquitano.health.infrastructure.config.DatabaseConfig
import me.aquitano.health.infrastructure.database.FlywayMigrator
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.util.Collections
import java.util.UUID

object PostgresTestDatabase {
    private data class ExternalSchema(
        val jdbcUrl: String,
        val user: String,
        val password: String,
        val schema: String,
    )

    private val externalSchemas =
        Collections.synchronizedList(mutableListOf<ExternalSchema>())

    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:17-alpine").apply {
            withDatabaseName("postgres")
            withUsername("aqt_health")
            withPassword("aqt_health")
            start()
        }
    }

    // Migrating every fixture database dominated integration test time, so Testcontainers fixtures
    // are cloned from one database migrated per JVM. Schema fixtures on an external server still
    // migrate themselves.
    private val migratedTemplate: String by lazy {
        createContainerDatabase(TEMPLATE_DATABASE)
        FlywayMigrator().migrate(containerConfig(TEMPLATE_DATABASE))
        TEMPLATE_DATABASE
    }

    /** A fixture database that may already be migrated. */
    fun config(): DatabaseConfig = fixtureConfig(migrated = true)

    /** A fixture database with no migrations applied, for tests that migrate step by step. */
    fun emptyConfig(): DatabaseConfig = fixtureConfig(migrated = false)

    private fun fixtureConfig(migrated: Boolean): DatabaseConfig {
        val configuredJdbcUrl = System.getenv("AQT_HEALTH_TEST_JDBC_URL")
        val configuredUser = System.getenv("AQT_HEALTH_TEST_DB_USER") ?: "aqt_health"
        val configuredPassword = System.getenv("AQT_HEALTH_TEST_DB_PASSWORD") ?: "aqt_health"
        externalConfig(
            jdbcUrl = configuredJdbcUrl ?: LOCAL_JDBC_URL,
            user = configuredUser,
            password = configuredPassword,
            required = configuredJdbcUrl != null,
        )?.let { return it }

        if (!dockerIsAvailable()) {
            throw IllegalStateException(MISSING_DATABASE_MESSAGE)
        }

        val databaseName = "aqt_health_test_${UUID.randomUUID().toString().replace("-", "")}"
        try {
            createContainerDatabase(databaseName, template = if (migrated) migratedTemplate else null)
        } catch (exception: Exception) {
            throw IllegalStateException(MISSING_DATABASE_MESSAGE, exception)
        }
        return containerConfig(databaseName)
    }

    private fun createContainerDatabase(
        name: String,
        template: String? = null,
    ) {
        adminConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE DATABASE $name" + (template?.let { " TEMPLATE $it" } ?: ""))
            }
        }
    }

    private fun containerConfig(databaseName: String): DatabaseConfig =
        DatabaseConfig(
            jdbcUrl = "jdbc:postgresql://${container.host}:${container.getMappedPort(5432)}/$databaseName",
            driver = "org.postgresql.Driver",
            user = container.username,
            password = container.password,
            maxPoolSize = 1,
        )

    fun connection(config: DatabaseConfig): Connection = DriverManager.getConnection(config.jdbcUrl, config.user, config.password)

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

    internal fun externalConfig(
        jdbcUrl: String,
        user: String,
        password: String,
        required: Boolean,
    ): DatabaseConfig? {
        val schema = "aqt_health_test_${UUID.randomUUID().toString().replace("-", "")}"
        val extensionSchema: String
        try {
            DriverManager
                .getConnection(jdbcUrl.withJdbcParameter("connectTimeout", "1"), user, password)
                .use { connection ->
                    connection.autoCommit = false
                    connection.createStatement().use { statement ->
                        // Extensions belong to the database, so disposable fixture schemas
                        // must not own their operator classes. The lock also covers other JVMs.
                        statement.execute("SELECT pg_advisory_xact_lock(718204, 1)")
                        statement.execute("CREATE SCHEMA IF NOT EXISTS $EXTENSIONS_SCHEMA")
                        statement.execute("CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA $EXTENSIONS_SCHEMA")
                        extensionSchema = statement.rescueExtensionFromFixtureSchema()
                        statement.execute("CREATE SCHEMA $schema")
                    }
                    connection.commit()
                }
        } catch (exception: SQLException) {
            if (required) {
                throw IllegalStateException(
                    "AQT_HEALTH_TEST_JDBC_URL is set, but the configured PostgreSQL database is not reachable.",
                    exception,
                )
            }
            return null
        }
        externalSchemas.add(ExternalSchema(jdbcUrl, user, password, schema))
        return DatabaseConfig(
            jdbcUrl = jdbcUrl.withJdbcParameter("currentSchema", "$schema,$extensionSchema"),
            driver = "org.postgresql.Driver",
            user = user,
            password = password,
            maxPoolSize = 1,
        )
    }

    /** A fixture schema left behind by a killed run may still own the extension. */
    private fun Statement.rescueExtensionFromFixtureSchema(): String {
        val owner =
            executeQuery(
                """
                SELECT n.nspname FROM pg_extension e
                JOIN pg_namespace n ON n.oid = e.extnamespace
                WHERE e.extname = 'btree_gist'
                """.trimIndent(),
            ).use { rows ->
                rows.next()
                rows.getString(1)
            }
        if (owner == EXTENSIONS_SCHEMA || !owner.startsWith("aqt_health_test_")) return owner
        execute("ALTER EXTENSION btree_gist SET SCHEMA $EXTENSIONS_SCHEMA")
        return EXTENSIONS_SCHEMA
    }

    private fun dockerIsAvailable(): Boolean =
        listOf(
            listOf("docker", "info"),
            listOf("/usr/local/bin/docker", "info"),
            listOf("/opt/homebrew/bin/docker", "info"),
        ).any { command ->
            try {
                ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor() == 0
            } catch (_: Exception) {
                false
            }
        }

    private fun String.withJdbcParameter(
        name: String,
        value: String,
    ): String {
        val parameters =
            substringAfter('?', "")
                .split('&')
                .filter { it.isNotEmpty() && it.substringBefore('=') != name }
        return substringBefore('?') + "?" + (parameters + "$name=$value").joinToString("&")
    }

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                externalSchemas.toList().forEach { externalSchema ->
                    DriverManager
                        .getConnection(
                            externalSchema.jdbcUrl,
                            externalSchema.user,
                            externalSchema.password,
                        ).use { connection ->
                            connection.createStatement().use { statement ->
                                statement.execute(
                                    "DROP SCHEMA IF EXISTS ${externalSchema.schema} CASCADE",
                                )
                            }
                        }
                }
            },
        )
    }

    internal const val EXTENSIONS_SCHEMA = "aqt_health_test_extensions"

    private const val TEMPLATE_DATABASE = "aqt_health_template"

    private const val LOCAL_JDBC_URL =
        "jdbc:postgresql://localhost:5432/aqt_health"

    private const val MISSING_DATABASE_MESSAGE =
        "PostgreSQL integration tests require Docker or a reachable " +
            "AQT_HEALTH_TEST_JDBC_URL/local PostgreSQL database."
}
