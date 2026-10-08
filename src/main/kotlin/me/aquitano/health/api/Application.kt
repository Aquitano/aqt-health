package me.aquitano.health.api

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.*
import me.aquitano.external.google.GeneratedGoogleHealthClient
import me.aquitano.external.google.GoogleHealthProvider
import me.aquitano.external.withings.WithingsProvider
import me.aquitano.health.application.*
import me.aquitano.health.di.adminReplayModule
import me.aquitano.health.di.coreModule
import me.aquitano.health.di.ingestionModule
import me.aquitano.health.di.metricsReadModule
import me.aquitano.health.di.providersModule
import me.aquitano.health.infrastructure.config.toAppConfig
import me.aquitano.health.infrastructure.database.DatabaseFactory
import me.aquitano.health.infrastructure.logging.*
import me.aquitano.health.infrastructure.repositories.SupportRepository
import me.aquitano.health.infrastructure.security.ApiKeyHasher
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import java.time.Clock

private val logger = KotlinLogging.logger("me.aquitano.health.api.Application")

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain
        .main(args)
}

fun Application.module() {
    configureObservability()
    val appConfig = environment.config.toAppConfig()
    logger.info { "app_starting" }

    val databaseFactory = DatabaseFactory()
    val database = databaseFactory.initialize(appConfig.database)

    monitor.subscribe(ApplicationStopped) {
        databaseFactory.close()
    }

    install(Koin) {
        slf4jLogger()
        modules(
            coreModule(database, appConfig),
            ingestionModule(),
            metricsReadModule(),
            providersModule(appConfig),
            adminReplayModule(),
        )
    }

    val googleHealthProvider by inject<GoogleHealthProvider>()
    val withingsProvider by inject<WithingsProvider>()
    logger.infoWithContext(
        "app_configured",
        "googleHealthConfigured" to googleHealthProvider.isConfigured(),
        "withingsConfigured" to withingsProvider.isConfigured(),
    )

    val httpClient by inject<io.ktor.client.HttpClient>()
    val clock by inject<Clock>()
    monitor.subscribe(ApplicationStopping) {
        httpClient.close()
    }

    // Holds one Google Health transport across syncs. Its ApplicationStopping handler is
    // registered after the sync producers below so the transport is released last: handlers run in
    // registration order, and closing it first would fail syncs that are still winding down.
    val googleHealthClient by inject<GeneratedGoogleHealthClient>()

    // Start the scheduled sync background job
    val scheduler by inject<ScheduledProviderSyncScheduler>()
    scheduler.start()
    monitor.subscribe(ApplicationStopping) {
        scheduler.stop()
    }

    // Retry derived rebuilds that failed after their ingestion batch committed
    val pendingDerivedRebuildSweeper by inject<PendingDerivedRebuildSweeper>()
    pendingDerivedRebuildSweeper.start()
    monitor.subscribe(ApplicationStopping) {
        pendingDerivedRebuildSweeper.stop()
    }

    val providerSyncJobService by inject<ProviderSyncJobService>()
    providerSyncJobService.start(clock.instant())
    monitor.subscribe(ApplicationStopping) {
        providerSyncJobService.stop()
    }

    val replayService by inject<ReplayService>()
    replayService.start(clock.instant())
    monitor.subscribe(ApplicationStopping) {
        replayService.stop()
    }

    monitor.subscribe(ApplicationStopping) {
        googleHealthClient.close()
    }

    // Re-upsert metric_catalog and provider_ranks from the Kotlin registry
    val metricCatalogBootstrap by inject<MetricCatalogBootstrap>()
    metricCatalogBootstrap.run()

    // Bootstrap the API client (creates a default API key if none exists)
    val bootstrapService by inject<ApiClientBootstrapService>()
    bootstrapService.bootstrap()

    val supportRepository by inject<SupportRepository>()
    val apiKeyHasher by inject<ApiKeyHasher>()

    configureHttp()
    configureAuthentication(
        supportRepository = supportRepository,
        apiKeyHasher = apiKeyHasher,
        clock = clock,
    )
    configureRoutes(appConfig = appConfig)
}
