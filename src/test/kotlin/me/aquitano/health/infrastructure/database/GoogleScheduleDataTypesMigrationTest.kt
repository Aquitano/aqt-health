package me.aquitano.health.infrastructure.database

import kotlinx.coroutines.runBlocking
import me.aquitano.health.infrastructure.repositories.ScheduledSyncRepository
import me.aquitano.health.test.PostgresIntegrationTest
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleScheduleDataTypesMigrationTest : PostgresIntegrationTest() {
    @Test
    fun schedulesOnThePreviousGoogleDefaultsGainHrvAndRespiratoryRateOnce() =
        runBlocking {
            val database = openDatabase()
            val repository = ScheduledSyncRepository(database)
            val now = Instant.parse("2026-10-01T00:00:00Z")
            val previousDefaults = listOf("steps", "sleep", "heart-rate", "weight", "body-fat")
            listOf("full" to previousDefaults, "subset" to listOf("steps", "sleep")).forEach { (instance, dataTypes) ->
                repository.upsertConfig("google-health", instance, true, dataTypes, 1_440, 7, now, now)
            }
            val migration =
                checkNotNull(javaClass.getResource("/db/migration/V35__schedule_google_hrv_and_respiratory_rate.sql")).readText()

            repeat(2) { transaction(database) { exec(migration) } }

            assertEquals(
                previousDefaults + listOf("heart-rate-variability", "respiratory-rate-sleep-summary"),
                repository.getConfig("google-health", "full")?.dataTypes,
            )
            assertEquals(listOf("steps", "sleep"), repository.getConfig("google-health", "subset")?.dataTypes)
        }
}
