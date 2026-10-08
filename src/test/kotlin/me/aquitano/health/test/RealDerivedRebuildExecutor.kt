package me.aquitano.health.test

import me.aquitano.health.application.DerivedRebuildExecutor
import me.aquitano.health.application.metric.steps.derived.CanonicalStepDerivationService
import me.aquitano.health.application.metric.steps.repository.CanonicalStepDerivationRepository
import org.jetbrains.exposed.v1.jdbc.Database

/** The production rebuild wiring with default-constructed services, for tests that assert derived tables. */
fun realDerivedRebuildExecutor(database: Database): DerivedRebuildExecutor = CanonicalStepDerivationService(database, CanonicalStepDerivationRepository())
