package me.aquitano.health.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
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
import kotlin.test.assertTrue

class ProductionRoutesTest {
    @Test
    fun productionServesTheSpecOnlyToApiClientsAndNoSwagger() =
        testApplication {
            environment { config = MapApplicationConfig() }
            application {
                install(Koin)
                install(Authentication) {
                    basic(ApiKeyAuthProviderName) { validate { if (it.password == "test-key") UserIdPrincipal(it.name) else null } }
                }
                configureRoutes(productionConfig())
            }

            assertEquals(HttpStatusCode.Unauthorized, client.get("/openapi").status)
            val spec = client.get("/openapi") { basicAuth("agent", "test-key") }
            assertEquals(HttpStatusCode.OK, spec.status)
            assertTrue("/api/v2/admin/health" in spec.bodyAsText())
            assertEquals(HttpStatusCode.NotFound, client.get("/swagger").status)
        }

    private fun productionConfig(): AppConfig =
        MapApplicationConfig(
            "aqtHealth.database.jdbcUrl" to "jdbc:postgresql://db.invalid/aqt_health",
            "aqtHealth.database.user" to "user",
            "aqtHealth.database.password" to "password",
            "aqtHealth.database.maxPoolSize" to "1",
            "aqtHealth.auth.bootstrapClientName" to "test-client",
            "aqtHealth.auth.bootstrapApiKey" to "test-key",
        ).toAppConfig().copy(environment = RuntimeEnvironment.PRODUCTION)
}
