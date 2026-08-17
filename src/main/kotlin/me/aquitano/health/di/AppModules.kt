package me.aquitano.health.di

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import me.aquitano.external.google.*
import me.aquitano.external.withings.KtorWithingsClient
import me.aquitano.external.withings.WITHINGS_PROVIDER_CODE
import me.aquitano.external.withings.WithingsNormalizer
import me.aquitano.external.withings.WithingsProvider
import me.aquitano.health.application.*
import me.aquitano.health.application.metric.activity.ActivityQueryService
import me.aquitano.health.application.metric.activity.repository.ActivitySummaryWriteRepository
import me.aquitano.health.application.metric.activity.repository.CanonicalActivitySummaryDerivationRepository
import me.aquitano.health.application.metric.cardiovascular.CardiovascularQueryService
import me.aquitano.health.application.metric.cardiovascular.repository.CardiovascularRepository
import me.aquitano.health.application.metric.cardiovascular.repository.CardiovascularWriteRepository
import me.aquitano.health.application.metric.common.MetricWriteService
import me.aquitano.health.application.metric.dashboard.DashboardQueryService
import me.aquitano.health.application.metric.scalar.ScalarMetricQueryService
import me.aquitano.health.application.metric.scalar.ScalarSampleReadRepository
import me.aquitano.health.application.metric.scalar.ScalarSampleWriteRepository
import me.aquitano.health.application.metric.sleep.SleepQueryService
import me.aquitano.health.application.metric.sleep.repository.CanonicalSleepSessionDerivationRepository
import me.aquitano.health.application.metric.sleep.repository.CanonicalSleepSummaryDerivationRepository
import me.aquitano.health.application.metric.sleep.repository.SleepRepository
import me.aquitano.health.application.metric.sleep.repository.SleepWriteRepository
import me.aquitano.health.application.metric.steps.StepQueryService
import me.aquitano.health.application.metric.steps.derived.CanonicalStepDerivationService
import me.aquitano.health.application.metric.steps.repository.CanonicalStepDerivationRepository
import me.aquitano.health.application.metric.steps.repository.StepDailySummaryDerivationRepository
import me.aquitano.health.application.metric.steps.repository.StepRepository
import me.aquitano.health.application.metric.steps.repository.StepWriteRepository
import me.aquitano.health.application.providersync.OAuthProviderSyncStore
import me.aquitano.health.application.providersync.ProviderSyncPipeline
import me.aquitano.health.application.providersync.ProviderSyncStore
import me.aquitano.health.infrastructure.config.AppConfig
import me.aquitano.health.infrastructure.repositories.IngestionRepository
import me.aquitano.health.infrastructure.repositories.PendingDerivedRebuildRepository
import me.aquitano.health.infrastructure.repositories.ProjectionWipeRepository
import me.aquitano.health.infrastructure.repositories.ProviderOAuthRepository
import me.aquitano.health.infrastructure.repositories.ProviderSyncIdempotencyRepository
import me.aquitano.health.infrastructure.repositories.ProviderSyncJobRepository
import me.aquitano.health.infrastructure.repositories.ReplayJobRepository
import me.aquitano.health.infrastructure.repositories.ScheduledSyncRepository
import me.aquitano.health.infrastructure.repositories.SupportRepository
import me.aquitano.health.infrastructure.security.ApiKeyHasher
import me.aquitano.health.infrastructure.time.UtcClock
import me.aquitano.health.shared.AppJson
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Cross-cutting platform beans shared by every feature: the [database] handle, clock, hashing,
 * the shared HTTP client, and the startup bootstrap helpers. Seeds are the [database] and [config]
 * from the bootstrap context; every other module resolves the [Database] as a bean.
 */
fun coreModule(database: Database, config: AppConfig) = module {
    single<Database> { database }
    single { UtcClock() }
    singleOf(::ApiKeyHasher)
    singleOf(::SupportRepository)
    singleOf(::MetricCatalogBootstrap)

    single<HttpClient> {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(AppJson) }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 60_000
                requestTimeoutMillis = 120_000
            }
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.INFO
                // Never emit bearer tokens to logs/OpenObserve. The Ktor logger writes headers as
                // free-text lines the logback JSON-field masker can't see, so mask the value here —
                // this also holds if the level is later raised to HEADERS/ALL for debugging.
                sanitizeHeader { header -> header.equals(HttpHeaders.Authorization, ignoreCase = true) }
            }
        }
    }

    single {
        ApiClientBootstrapService(
            authConfig = config.auth,
            supportRepository = get(),
            apiKeyHasher = get(),
            clock = get(),
        )
    }
}

/**
 * Ingestion write path: raw-batch storage, metric write repositories, the derived-projection
 * rebuild machinery, and the services that turn a normalized batch into stored metrics.
 */
fun ingestionModule() = module {
    singleOf(::IngestionRepository)
    singleOf(::IngestionMappingService)

    // Metric write repositories
    singleOf(::ActivitySummaryWriteRepository)
    singleOf(::CardiovascularWriteRepository)
    singleOf(::ScalarSampleWriteRepository)
    singleOf(::SleepWriteRepository)
    singleOf(::StepWriteRepository)

    // Derived-projection rebuild
    singleOf(::PendingDerivedRebuildRepository)
    singleOf(::ProjectionWipeRepository)
    singleOf(::StepDailySummaryDerivationRepository)
    single { StepSummaryService(get<StepDailySummaryDerivationRepository>()) }
    single { CanonicalStepDerivationService(get<CanonicalStepDerivationRepository>()) }
    single {
        DerivedRebuildModuleRegistry(
            derivedRebuildModules(
                stepSummaryService = get(),
                canonicalStepService = get(),
            )
        )
    }
    singleOf(::TransactionalDerivedRebuildExecutor) { bind<DerivedRebuildExecutor>() }
    single {
        PendingDerivedRebuildSweeper(
            repository = get(),
            derivedRebuildExecutor = get(),
            clock = get(),
        )
    }

    singleOf(::MetricWriteService)
    singleOf(::IngestionService)
}

/**
 * Read side: read repositories, the health-day module registry, and the query services that back
 * the metric, structural, dashboard, and trend read routes.
 */
fun metricsReadModule() = module {
    // Read repositories
    singleOf(::CanonicalActivitySummaryDerivationRepository)
    singleOf(::CardiovascularRepository)
    singleOf(::ScalarSampleReadRepository)
    singleOf(::SleepRepository)
    singleOf(::StepRepository)
    singleOf(::CanonicalStepDerivationRepository)
    singleOf(::CanonicalSleepSessionDerivationRepository)
    singleOf(::CanonicalSleepSummaryDerivationRepository)

    single {
        HealthDayModuleRegistry(
            listOf(
                StepsDayModule(get()),
                HeartRateDayModule(get()),
                WeightDayModule(get()),
                SleepDayModule(get()),
            )
        )
    }

    // Query services
    singleOf(::ActivityQueryService)
    singleOf(::CardiovascularQueryService)
    singleOf(::ScalarMetricQueryService)
    singleOf(::SleepQueryService)
    singleOf(::StepQueryService)
    singleOf(::DashboardQueryService)
    singleOf(::SleepSummaryReadService)
    singleOf(::HealthDayQueryService)
    singleOf(::TrendQueryService)
}

/**
 * External health providers: OAuth/sync persistence, provider HTTP clients, the shared sync
 * pipeline, and the provider-facing discovery, status, workflow, and scheduling services.
 */
fun providersModule(config: AppConfig) = module {
    // OAuth + sync persistence
    singleOf(::ProviderOAuthRepository)
    singleOf(::ProviderSyncJobRepository)
    singleOf(::ProviderSyncIdempotencyRepository)
    singleOf(::ScheduledSyncRepository)
    singleOf(::ScheduledSyncRunGuard)

    // External provider HTTP clients
    single<GoogleHealthOAuthClient> {
        KtorGoogleHealthOAuthClient(get(), config.googleHealth)
    }
    single {
        GeneratedGoogleHealthClient(
            oauthClient = get(),
            dataPointsServiceFactory = GoogleHealthDataPointsServiceFactory(
                config.googleHealth.apiBaseUrl
            ),
        )
    }
    single { KtorWithingsClient(get(), config.withings) }

    // Provider sync pipeline. One store and one pipeline serve every provider; the store picks
    // the token cipher per provider code from the configured encryption keys.
    single<ProviderSyncStore> {
        OAuthProviderSyncStore(
            repository = get(),
            ingestionService = get(),
            tokenEncryptionKeys = mapOf(
                GOOGLE_HEALTH_PROVIDER_CODE to config.googleHealth.tokenEncryptionKey,
                WITHINGS_PROVIDER_CODE to config.withings.tokenEncryptionKey,
            ),
        )
    }
    single { ProviderSyncPipeline(store = get()) }

    // Providers
    single {
        GoogleHealthProvider(
            config = config.googleHealth,
            repository = get(),
            client = get<GeneratedGoogleHealthClient>(),
            normalizer = GoogleHealthNormalizer(),
            syncPipeline = get(),
        )
    }
    single {
        WithingsProvider(
            config = config.withings,
            repository = get(),
            client = get<KtorWithingsClient>(),
            normalizer = WithingsNormalizer(),
            syncPipeline = get(),
        )
    }
    single {
        HealthProviderRegistry(
            listOf(
                get<GoogleHealthProvider>(),
                get<WithingsProvider>(),
            )
        )
    }

    // Provider-facing services
    singleOf(::ProviderStatusService)
    singleOf(::ProviderDiscoveryService)
    singleOf(::ProviderWorkflowService)
    single {
        ProviderSyncJobService(
            providerRegistry = get(),
            workflowService = get(),
            repository = get(),
            clock = get(),
        )
    }
    singleOf(::ScheduledProviderSyncService)
    single {
        ScheduledProviderSyncScheduler(
            service = get(),
            clock = get(),
        )
    }
}

/**
 * Replay and admin: ingestion-batch inspection plus the projection/derived replay job runner.
 */
fun adminReplayModule() = module {
    singleOf(::ReplayJobRepository)
    singleOf(::AdminService)
    single {
        ReplayService(
            database = get(),
            ingestionRepository = get(),
            mappingService = get(),
            metricWriteService = get(),
            derivedRebuildExecutor = get(),
            derivedRebuildRegistry = get(),
            replayJobRepository = get(),
            projectionWipeRepository = get(),
            clock = get(),
        )
    }
}
