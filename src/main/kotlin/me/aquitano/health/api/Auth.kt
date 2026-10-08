package me.aquitano.health.api

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.BadRequestException
import me.aquitano.health.infrastructure.repositories.SupportRepository
import me.aquitano.health.infrastructure.security.ApiKeyHasher
import me.aquitano.health.infrastructure.time.UtcClock

/** Matches the OpenAPI security scheme name so inferred route security stays consistent. */
const val ApiKeyAuthProviderName = BearerApiKeySecurityScheme

fun Application.configureAuthentication(
    supportRepository: SupportRepository,
    apiKeyHasher: ApiKeyHasher,
    clock: UtcClock,
) {
    install(Authentication) {
        bearer(
            name = ApiKeyAuthProviderName,
            description = "Use `Authorization: Bearer <api-key>` with an API key registered in aqt-health.",
        ) {
            // Ktor answers an unparseable Authorization header with 400; keep it a 401 like any bad key.
            authHeader { call ->
                try {
                    call.request.parseAuthorizationHeader()
                } catch (_: BadRequestException) {
                    null
                }
            }
            authenticate { credential ->
                supportRepository.findEnabledApiClientByHash(apiKeyHasher.hash(credential.token), clock.now())
            }
        }
    }
}
