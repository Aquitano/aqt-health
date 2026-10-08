package me.aquitano.health.test

import me.aquitano.health.application.DerivedRebuildExecutor
import me.aquitano.health.application.IngestionMappingService
import me.aquitano.health.application.IngestionService
import me.aquitano.health.application.metric.activity.repository.ActivitySummaryWriteRepository
import me.aquitano.health.application.metric.cardiovascular.repository.CardiovascularWriteRepository
import me.aquitano.health.application.metric.common.MetricWriteService
import me.aquitano.health.application.metric.scalar.ScalarSampleWriteRepository
import me.aquitano.health.application.metric.sleep.repository.SleepWriteRepository
import me.aquitano.health.application.metric.steps.repository.StepWriteRepository
import me.aquitano.health.infrastructure.repositories.IngestionRepository
import me.aquitano.health.infrastructure.repositories.PendingDerivedRebuildRepository
import me.aquitano.health.infrastructure.repositories.SupportRepository
import org.jetbrains.exposed.v1.jdbc.Database

/** The production write wiring with default-constructed repositories, for ingestion tests. */
fun metricWriteService(): MetricWriteService =
    MetricWriteService(
        stepWriteRepository = StepWriteRepository(),
        sleepWriteRepository = SleepWriteRepository(),
        activitySummaryWriteRepository = ActivitySummaryWriteRepository(),
        cardiovascularWriteRepository = CardiovascularWriteRepository(),
        scalarSampleWriteRepository = ScalarSampleWriteRepository(),
        derivedRebuildRegistry = derivedRebuildRegistry(),
    )

fun ingestionService(
    database: Database,
    derivedRebuildExecutor: DerivedRebuildExecutor = NoOpDerivedRebuildExecutor,
): IngestionService =
    IngestionService(
        database = database,
        mappingService = IngestionMappingService(),
        supportRepository = SupportRepository(database),
        ingestionRepository = IngestionRepository(),
        metricWriteService = metricWriteService(),
        derivedRebuildExecutor = derivedRebuildExecutor,
        pendingDerivedRebuildRepository = PendingDerivedRebuildRepository(database),
    )
