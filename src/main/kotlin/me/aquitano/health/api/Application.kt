package me.aquitano.health.api

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import me.aquitano.health.application.*
import me.aquitano.health.infrastructure.config.toAppConfig
import me.aquitano.health.infrastructure.database.DatabaseFactory
import me.aquitano.health.infrastructure.repositories.MetricsWriteRepository
import me.aquitano.health.infrastructure.repositories.IngestionRepository
import me.aquitano.health.infrastructure.repositories.MetricsReadRepository
import me.aquitano.health.infrastructure.repositories.ProviderOAuthRepository
import me.aquitano.health.infrastructure.repositories.SupportRepository
import me.aquitano.health.infrastructure.providers.googlehealth.GoogleHealthNormalizer
import me.aquitano.health.infrastructure.providers.googlehealth.GoogleHealthOAuthService
import me.aquitano.health.infrastructure.providers.googlehealth.GoogleHealthSyncService
import me.aquitano.health.infrastructure.providers.googlehealth.KtorGoogleHealthClient
import me.aquitano.health.infrastructure.providers.withings.KtorWithingsClient
import me.aquitano.health.infrastructure.providers.withings.WithingsNormalizer
import me.aquitano.health.infrastructure.providers.withings.WithingsOAuthService
import me.aquitano.health.infrastructure.providers.withings.WithingsSyncService
import me.aquitano.health.infrastructure.security.ApiKeyHasher
import me.aquitano.health.infrastructure.time.UtcClock
import me.aquitano.health.shared.AppJson
import org.jetbrains.exposed.sql.Database

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    val clock = UtcClock()
    val appConfig = environment.config.toAppConfig()
    val database = DatabaseFactory().initialize(appConfig.database)
    val apiKeyHasher = ApiKeyHasher()
    val supportRepository = SupportRepository(database)
    val metricsWriteRepository = MetricsWriteRepository()
    val ingestionRepository = IngestionRepository()
    val providerOAuthRepository = ProviderOAuthRepository(database)
    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(AppJson)
        }
    }
    val googleHealthClient = KtorGoogleHealthClient(httpClient, appConfig.googleHealth)
    val withingsClient = KtorWithingsClient(httpClient, appConfig.withings)
    val ingestionService = IngestionService(
        database = database,
        mappingService = IngestionMappingService(),
        supportRepository = supportRepository,
        ingestionRepository = ingestionRepository,
        metricsWriteRepository = metricsWriteRepository,
        stepSummaryService = StepSummaryService(metricsWriteRepository),
    )
    val services = ApplicationServices(
        database = database,
        supportRepository = supportRepository,
        apiKeyHasher = apiKeyHasher,
        clock = clock,
        ingestionService = ingestionService,
        metricsQueryService = MetricsQueryService(
            database = database,
            metricsReadRepository = MetricsReadRepository(),
        ),
        adminService = AdminService(
            database = database,
            ingestionRepository = ingestionRepository,
        ),
        googleHealthOAuthService = GoogleHealthOAuthService(
            config = appConfig.googleHealth,
            repository = providerOAuthRepository,
            client = googleHealthClient,
        ),
        googleHealthSyncService = GoogleHealthSyncService(
            config = appConfig.googleHealth,
            repository = providerOAuthRepository,
            client = googleHealthClient,
            normalizer = GoogleHealthNormalizer(),
            ingestionService = ingestionService,
        ),
        withingsOAuthService = WithingsOAuthService(
            config = appConfig.withings,
            repository = providerOAuthRepository,
            client = withingsClient,
        ),
        withingsSyncService = WithingsSyncService(
            config = appConfig.withings,
            repository = providerOAuthRepository,
            client = withingsClient,
            normalizer = WithingsNormalizer(),
            ingestionService = ingestionService,
        ),
    )

    ApiClientBootstrapService(
        authConfig = appConfig.auth,
        supportRepository = supportRepository,
        apiKeyHasher = apiKeyHasher,
        clock = clock,
    ).bootstrap()

    configureHttp()
    configureRoutes(services = services)
}

data class ApplicationServices(
    val database: Database,
    val supportRepository: SupportRepository,
    val apiKeyHasher: ApiKeyHasher,
    val clock: UtcClock,
    val ingestionService: IngestionService,
    val metricsQueryService: MetricsQueryService,
    val adminService: AdminService,
    val googleHealthOAuthService: GoogleHealthOAuthService,
    val googleHealthSyncService: GoogleHealthSyncService,
    val withingsOAuthService: WithingsOAuthService,
    val withingsSyncService: WithingsSyncService,
)
