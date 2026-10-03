package me.aquitano.health.test

import me.aquitano.health.application.DerivedRebuildExecutor
import me.aquitano.health.application.DerivedRebuildModuleRegistry
import me.aquitano.health.application.PerDateDerivedRebuildExecutor
import me.aquitano.health.application.derivedRebuildModules
import me.aquitano.health.application.metric.steps.derived.CanonicalStepDerivationService
import me.aquitano.health.application.metric.steps.repository.CanonicalStepDerivationRepository
import org.jetbrains.exposed.v1.jdbc.Database

/** The production module wiring with default-constructed services, for tests. */
fun derivedRebuildRegistry(): DerivedRebuildModuleRegistry =
    DerivedRebuildModuleRegistry(
        derivedRebuildModules(CanonicalStepDerivationService(CanonicalStepDerivationRepository()))
    )

/** The production rebuild wiring with default-constructed services, for tests that assert derived tables. */
fun realDerivedRebuildExecutor(database: Database): DerivedRebuildExecutor =
    PerDateDerivedRebuildExecutor(
        database = database,
        registry = derivedRebuildRegistry(),
    )
