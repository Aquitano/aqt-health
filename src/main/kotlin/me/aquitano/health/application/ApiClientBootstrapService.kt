package me.aquitano.health.application

import me.aquitano.health.infrastructure.config.AuthConfig
import me.aquitano.health.infrastructure.repositories.BootstrapApiClientOutcome
import me.aquitano.health.infrastructure.repositories.SupportRepository
import me.aquitano.health.infrastructure.security.ApiKeyHasher
import me.aquitano.health.infrastructure.time.UtcClock
import io.github.oshai.kotlinlogging.KotlinLogging
import me.aquitano.health.infrastructure.logging.*

private val logger = KotlinLogging.logger {}

class ApiClientBootstrapService(
    private val authConfig: AuthConfig,
    private val supportRepository: SupportRepository,
    private val apiKeyHasher: ApiKeyHasher,
    private val clock: UtcClock,
) {
    fun bootstrap() {
        val bootstrapApiKey = authConfig.bootstrapApiKey
        if (bootstrapApiKey.isBlank()) {
            logger.infoWithContext(
                "api_client_bootstrap_skipped",
                "clientName" to authConfig.bootstrapClientName,
            )
            return
        }

        val outcome = supportRepository.upsertBootstrapApiClient(
            name = authConfig.bootstrapClientName,
            apiKeyHash = apiKeyHasher.hash(bootstrapApiKey),
            now = clock.now(),
        )
        logger.infoWithContext(
            when (outcome) {
                BootstrapApiClientOutcome.CREATED -> "api_client_bootstrap_created"
                BootstrapApiClientOutcome.ROTATED -> "api_client_bootstrap_rotated"
                BootstrapApiClientOutcome.UNCHANGED -> "api_client_bootstrap_skipped"
            },
            "clientName" to authConfig.bootstrapClientName,
        )
    }
}
