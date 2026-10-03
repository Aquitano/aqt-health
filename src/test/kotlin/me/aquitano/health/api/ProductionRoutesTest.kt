package me.aquitano.health.api

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import me.aquitano.health.infrastructure.config.AppConfig
import me.aquitano.health.infrastructure.config.RuntimeEnvironment
import me.aquitano.health.infrastructure.config.toAppConfig
import org.koin.ktor.plugin.Koin
import kotlin.test.Test
import kotlin.test.assertEquals

class ProductionRoutesTest {
    @Test
    fun openApiAndSwaggerAreNotServedInProduction() =
        testApplication {
            environment { config = MapApplicationConfig() }
            application {
                install(Koin)
                install(Authentication) { basic(ApiKeyAuthProviderName) { validate { null } } }
                configureRoutes(productionConfig())
            }

            assertEquals(HttpStatusCode.NotFound, client.get("/openapi").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/swagger").status)
        }

    private fun productionConfig(): AppConfig =
        MapApplicationConfig(
            "aqtHealth.database.jdbcUrl" to "jdbc:postgresql://db.invalid/aqt_health",
            "aqtHealth.database.driver" to "org.postgresql.Driver",
            "aqtHealth.database.user" to "user",
            "aqtHealth.database.password" to "password",
            "aqtHealth.database.maxPoolSize" to "1",
            "aqtHealth.auth.bootstrapClientName" to "test-client",
            "aqtHealth.auth.bootstrapApiKey" to "test-key",
        ).toAppConfig().copy(environment = RuntimeEnvironment.PRODUCTION)
}
