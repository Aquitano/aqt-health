package me.aquitano.health.application

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import me.aquitano.health.api.dto.*
import me.aquitano.health.application.metric.common.MetricWrite
import me.aquitano.health.application.metric.steps.derived.CanonicalStepDerivationService
import me.aquitano.health.application.metric.steps.repository.CanonicalStepDerivationRepository
import me.aquitano.health.domain.ReplayJobStatus
import me.aquitano.health.infrastructure.database.suspendDbTransaction
import me.aquitano.health.infrastructure.repositories.*
import me.aquitano.health.infrastructure.time.UtcClock
import me.aquitano.health.test.*
import org.junit.After
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class ProviderCorrectionTest : PostgresIntegrationTest() {
    private val replayServices = mutableListOf<ReplayService>()

    @After
    fun stopReplayServices() = replayServices.forEach { it.stop() }

    @Test
    fun allIdentifiedProjectionsUseNewValuesAndKeepOriginalHistory() = runBlocking {
        val fixture = Fixture()
        val original = recordsForDay("2026-04-19", corrected = false)
        val corrected = recordsForDay("2026-04-17", corrected = true)
        fixture.ingest(original)
        fixture.ingest(corrected)
        fixture.assertCorrectedValues()
        assertEquals(12, fixture.number("SELECT COUNT(*) FROM ingestion_records"))
        assertEquals(100, fixture.number("SELECT (normalized_record_json->>'steps')::int FROM ingestion_records WHERE record_type = 'step_interval' ORDER BY id LIMIT 1"))
        val projectionIds = fixture.projectionIds()

        val duplicate = fixture.ingest(corrected)
        assertEquals(6, duplicate.metricsSkipped.duplicates)
        assertEquals(0, duplicate.metricsCreated.values.sum())
        assertEquals(projectionIds, fixture.projectionIds())
        assertEquals(18, fixture.number("SELECT COUNT(*) FROM ingestion_records"))
        assertEquals(0, fixture.replay(ReplayRequest(scope = "all")).metricsWritten)

        fixture.replay(ReplayRequest(scope = "all", wipe = true))
        fixture.assertCorrectedValues()
        assertEquals(18, fixture.number("SELECT COUNT(*) FROM ingestion_records"))
        projectionTables.forEach { assertEquals(1, fixture.number("SELECT COUNT(*) FROM $it")) }
    }

    @Test
    fun movedStepCorrectionInvalidatesBothDatesAndCannotBeRevertedByOlderReplay() = runBlocking {
        val fixture = Fixture()
        suspend fun steps(day: String, count: Int) = fixture.ingest(listOf(
            StepInterval("moving-steps", "${day}T08:00:00Z", "${day}T09:00:00Z", count)
        ))
        steps("2026-04-18", 100)
        val firstId = fixture.number("SELECT id FROM step_samples")
        val moved = steps("2026-04-20", 200)
        assertEquals(setOf("2026-04-18", "2026-04-20"), moved.affectedStepSummaryDates.toSet())
        assertNotEquals(firstId, fixture.number("SELECT id FROM step_samples"))
        assertEquals(0, fixture.number("SELECT COUNT(*) FROM step_daily_summaries WHERE date = '2026-04-18'"))
        assertEquals(0, fixture.number("SELECT COUNT(*) FROM canonical_step_samples WHERE date = '2026-04-18'"))
        assertEquals(200, fixture.number("SELECT steps FROM step_daily_summaries"))

        // The newest arrival is on an earlier date, so chronological replay sees it first.
        steps("2026-04-17", 300)
        fixture.replay(ReplayRequest(scope = "all", fromDate = "2026-04-20", toDate = "2026-04-20", wipe = true))
        assertEquals(300, fixture.number("SELECT steps FROM step_samples"))
        fixture.replay(ReplayRequest(scope = "all", wipe = true))
        assertEquals(300, fixture.number("SELECT steps FROM step_samples"))
        assertEquals("2026-04-17", fixture.text("SELECT date::text FROM step_daily_summaries"))
        assertEquals(300, fixture.number("SELECT SUM(value)::int FROM canonical_step_day_bucket_contributions"))
        assertEquals(1, fixture.number("SELECT COUNT(*) FROM step_daily_summaries"))
    }

    @Test
    fun scalarCorrectionKeepsContextSegmentMetricAndSourceIdentitiesSeparate() = runBlocking {
        val fixture = Fixture()
        suspend fun scalar(metric: String, value: Double, context: String? = null, segment: String? = null, source: String = "scale") =
            fixture.ingest(listOf(ScalarSample("shared", "2026-04-19T10:00:00Z", metric, value, context = context, segment = segment)), source = source)
        scalar("heart_rate", 65.0)
        val originalId = fixture.number("SELECT id FROM scalar_samples")
        val same = scalar("heart_rate", 65.0, context = "unknown")
        assertEquals(1, same.metricsSkipped.duplicates)
        assertEquals(originalId, fixture.number("SELECT id FROM scalar_samples"))
        scalar("heart_rate", 70.0, context = "sleep")
        scalar("heart_rate", 80.0)
        scalar("heart_rate", 90.0, source = "other")
        scalar("segmental_fat_mass", 2.0, segment = "left_arm")
        scalar("segmental_fat_mass", 3.0, segment = "right_arm")
        scalar("segmental_fat_mass", 4.0, segment = "left_arm")
        assertEquals(5, fixture.number("SELECT COUNT(*) FROM scalar_samples"))
        assertEquals(4, fixture.number("SELECT value::int FROM scalar_samples WHERE segment = 'left_arm'"))
        assertEquals(3, fixture.number("SELECT value::int FROM scalar_samples WHERE segment = 'right_arm'"))
        assertEquals(70, fixture.number("SELECT value::int FROM scalar_samples WHERE context = 'sleep'"))
        assertEquals(170, fixture.number("SELECT SUM(value)::int FROM scalar_samples WHERE context = 'unknown'"))
        fixture.replay(ReplayRequest(scope = "all", wipe = true))
        assertEquals(5, fixture.number("SELECT COUNT(*) FROM scalar_samples"))
        assertEquals(170, fixture.number("SELECT SUM(value)::int FROM scalar_samples WHERE context = 'unknown'"))
    }

    @Test
    fun scalarDateMoveAndFailedNewerArrivalDoNotResurrectOldValues() = runBlocking {
        val fixture = Fixture()
        val original = ScalarSample("weight", "2026-04-19T10:00:00Z", "weight", 80.0)
        val corrected = original.copy(measuredAt = "2026-04-17T10:00:00Z", value = 81.0)
        fixture.ingest(listOf(original))
        fixture.ingest(listOf(corrected))
        fixture.appendFailed(corrected.copy(value = 99.0))
        fixture.replay(ReplayRequest(scope = "all", fromDate = "2026-04-19", toDate = "2026-04-19", wipe = true))
        assertEquals(81, fixture.number("SELECT value::int FROM scalar_samples"))
        fixture.replay(ReplayRequest(scope = "all", wipe = true))
        assertEquals(81, fixture.number("SELECT value::int FROM scalar_samples"))
        assertEquals("2026-04-17", fixture.text("SELECT measured_at::date::text FROM scalar_samples"))
        assertEquals(3, fixture.number("SELECT COUNT(*) FROM ingestion_records"))
    }

    @Test
    fun correctedGoogleIntervalSurvivesOverlapsAndReplay() = runBlocking {
        val fixture = Fixture(provider = "google_health")
        fixture.ingest(listOf(StepInterval("first", "2026-04-19T08:00:00Z", "2026-04-19T09:00:00Z", 100)))
        fixture.ingest(listOf(StepInterval("neighbor", "2026-04-19T09:00:00Z", "2026-04-19T10:00:00Z", 200)))
        val corrected = StepInterval("first", "2026-04-19T08:00:00Z", "2026-04-19T09:30:00Z", 300)
        fixture.ingest(listOf(corrected))
        fixture.ingest(listOf(corrected))
        assertEquals(300, fixture.number("SELECT steps FROM step_samples WHERE provider_record_id = 'first'"))
        fixture.replay(ReplayRequest(scope = "all", wipe = true))
        assertEquals(300, fixture.number("SELECT steps FROM step_samples WHERE provider_record_id = 'first'"))
        assertEquals(2, fixture.number("SELECT COUNT(*) FROM step_samples"))
        fixture.ingest(listOf(corrected.copy(steps = 100)))
        fixture.ingest(listOf(corrected))
        fixture.ingest(listOf(corrected))
        fixture.replay(ReplayRequest(scope = "all", wipe = true))
        assertEquals(300, fixture.number("SELECT steps FROM step_samples WHERE provider_record_id = 'first'"))
        assertEquals(2, fixture.number("SELECT COUNT(*) FROM step_samples"))
    }

    @Test
    fun unchangedGoogleIntervalSkippedForOverlapStaysSkipped() = runBlocking {
        val fixture = Fixture(provider = "google_health")
        val neighbor = StepInterval("neighbor", "2026-04-19T08:00:00Z", "2026-04-19T10:00:00Z", 200)
        fixture.ingest(listOf(neighbor))
        val overlapping = StepInterval("skipped", "2026-04-19T09:00:00Z", "2026-04-19T10:00:00Z", 100)
        repeat(2) {
            assertEquals(1, fixture.ingest(listOf(overlapping)).metricsSkipped.duplicates)
            assertEquals(1, fixture.number("SELECT COUNT(*) FROM step_samples"))
        }
        fixture.replay(ReplayRequest(scope = "all", wipe = true))
        assertEquals(1, fixture.number("SELECT COUNT(*) FROM step_samples"))
        assertEquals("neighbor", fixture.text("SELECT provider_record_id FROM step_samples"))
        fixture.ingest(listOf(neighbor))
        fixture.replay(ReplayRequest(scope = "all", wipe = true))
        assertEquals(1, fixture.number("SELECT COUNT(*) FROM step_samples"))
        assertEquals("neighbor", fixture.text("SELECT provider_record_id FROM step_samples"))
        fixture.ingest(listOf(neighbor.copy(steps = 300)))
        fixture.replay(ReplayRequest(scope = "all", wipe = true))
        assertEquals(1, fixture.number("SELECT COUNT(*) FROM step_samples"))
        assertEquals(300, fixture.number("SELECT steps FROM step_samples"))
        assertEquals(2, fixture.number("SELECT COUNT(*) FROM ingestion_records WHERE google_step_projection_accepted = false"))
    }

    @Test
    fun acceptedGoogleCorrectionAcrossDaysKeepsSkippedNeighborsOutOfReplay() = runBlocking {
        val fixture = Fixture(provider = "google_health")
        fixture.ingest(listOf(StepInterval("winner", "2026-04-19T23:00:00Z", "2026-04-20T02:00:00Z", 300)))
        val skipped = StepInterval("skipped", "2026-04-20T01:00:00Z", "2026-04-20T02:00:00Z", 100)
        fixture.ingest(listOf(skipped))
        fixture.ingest(listOf(StepInterval("winner", "2026-04-18T23:00:00Z", "2026-04-19T02:00:00Z", 400)))
        fixture.replay(ReplayRequest(scope = "all", wipe = true))
        assertEquals(1, fixture.number("SELECT COUNT(*) FROM step_samples"))
        assertEquals("winner", fixture.text("SELECT provider_record_id FROM step_samples"))
        assertEquals(400, fixture.number("SELECT steps FROM step_samples"))
        // A new arrival for a previously skipped identity still gets the normal overlap check.
        fixture.ingest(listOf(skipped))
        assertEquals(2, fixture.number("SELECT COUNT(*) FROM step_samples"))
        fixture.replay(ReplayRequest(scope = "all", wipe = true))
        assertEquals(2, fixture.number("SELECT COUNT(*) FROM step_samples"))
    }

    @Test
    fun newestRecordWinsAcrossLookupChunks() = runBlocking {
        val fixture = Fixture()
        fixture.writeRawBatch(listOf(
            ScalarSample("repeated", "2026-04-19T10:00:00Z", "weight", 80.0),
        ) + (1..1001).map {
            ScalarSample("weight-$it", "2026-04-19T10:00:00Z", "weight", 70.0)
        } + ScalarSample("repeated", "2026-04-19T10:00:00Z", "weight", 81.0))
        assertEquals(1002, fixture.number("SELECT COUNT(*) FROM scalar_samples"))
        assertEquals(81, fixture.number("SELECT value::int FROM scalar_samples WHERE provider_record_id = 'repeated'"))
        assertEquals(1003, fixture.number("SELECT COUNT(*) FROM ingestion_records"))
        fixture.replay(ReplayRequest(scope = "all", wipe = true))
        assertEquals(81, fixture.number("SELECT value::int FROM scalar_samples WHERE provider_record_id = 'repeated'"))
    }

    @Test
    fun preparedCanonicalOutputIsRejectedAfterStepCorrection() = runBlocking {
        val fixture = Fixture(rebuildOnIngest = false, poolSize = 2)
        val day = LocalDate.parse("2030-03-01")
        val sample = StepInterval("steps", "2030-03-01T08:00:00Z", "2030-03-01T09:00:00Z", 100)
        fixture.ingest(listOf(sample))
        val derivation = CanonicalStepDerivationService(CanonicalStepDerivationRepository())
        PostgresTestDatabase.connection(fixture.config).use { lock ->
            lock.autoCommit = false
            lock.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(384729, ${day.toEpochDay()})") }
            val stale = async(Dispatchers.IO) {
                runCatching { derivation.recompute(fixture.database, setOf(day), fixture.now) }
            }
            try {
                withTimeout(10_000) {
                    while (fixture.number("SELECT COUNT(*) FROM pg_locks WHERE locktype = 'advisory' AND classid = 384729 AND objid = ${day.toEpochDay()} AND NOT granted") == 0) delay(20)
                }
                fixture.ingest(listOf(sample.copy(steps = 200)))
            } finally {
                lock.commit()
            }
            val failure = stale.await().exceptionOrNull()
            assertIs<IllegalStateException>(failure)
            assertTrue(failure.message!!.contains("changed during derivation"))
        }
        derivation.recompute(fixture.database, setOf(day), fixture.now)
        assertEquals(200, fixture.number("SELECT SUM(value)::int FROM canonical_step_day_bucket_contributions"))
    }

    private fun recordsForDay(day: String, corrected: Boolean): List<IngestionRecord> = listOf(
        StepInterval("steps", "${day}T08:00:00Z", "${day}T09:00:00Z", if (corrected) 200 else 100),
        SleepSession("sleep", "${day}T01:00:00Z", "${day}T07:00:00Z", listOf(
            SleepStage(if (corrected) "deep" else "light", "${day}T01:00:00Z", "${day}T07:00:00Z")
        )),
        SleepSummary("summary", "${day}T01:00:00Z", "${day}T07:00:00Z", sleepScore = if (corrected) 90 else 60),
        ActivitySummary("activity", day, distanceMeters = if (corrected) 2000.0 else 1000.0),
        BloodPressure("pressure", "${day}T10:00:00Z", if (corrected) 120 else 140, 80),
        ScalarSample("weight", "${day}T10:00:00Z", "weight", if (corrected) 81.0 else 80.0),
    )

    private inner class Fixture(private val provider: String = "withings", rebuildOnIngest: Boolean = true, poolSize: Int = 1) {
        val config = PostgresTestDatabase.config().copy(maxPoolSize = poolSize)
        val database = openDatabase(config)
        val now: Instant = Instant.parse("2031-01-01T00:00:00Z")
        private var batch = 0
        private val mapping = IngestionMappingService()
        private val records = IngestionRepository()
        private val writer = metricWriteService()
        private val derived = realDerivedRebuildExecutor(database)
        private val ingestion = IngestionService(
            database, mapping, SupportRepository(database), records, writer,
            if (rebuildOnIngest) derived else NoOpDerivedRebuildExecutor,
            PendingDerivedRebuildRepository(database),
        )
        private val replays = ReplayService(
            database, records, mapping, writer, derived, derivedRebuildRegistry(),
            ReplayJobRepository(database), ProjectionWipeRepository(), UtcClock.fixed(now),
        ).also { replayServices += it }

        suspend fun ingest(values: List<IngestionRecord>, source: String = "scale"): IngestionSummaryResponse =
            ingestion.ingestBatch(IngestionBatchRequest(
                provider, source, "correction-${++batch}", now.toString(), buildJsonObject {}, values,
            ), now.plusSeconds(batch.toLong()))

        suspend fun writeRawBatch(values: List<IngestionRecord>) = suspendDbTransaction(db = database) {
            val source = SupportRepository(database).resolveOrCreateSourceInstanceInTransaction(provider, "scale", now)
            val batches = values.chunked(1000).map { chunk ->
                val batchId = records.insertBatch(source.id, null, "{}", now, now)
                batchId to records.insertRecords(batchId, chunk.map { mapping.mapRecord(it)!! }, now)
            }
            writer.writeAll(provider, source.id, batches.flatMap { (_, inserted) -> inserted.map { MetricWrite(it.id, it.record) } }, now)
            batches.forEach { (batchId, _) -> records.markProcessed(batchId, now) }
        }

        suspend fun appendFailed(value: IngestionRecord) = suspendDbTransaction(db = database) {
            val batchId = records.insertBatch(number("SELECT id FROM source_instances LIMIT 1"), null, "{}", now, now)
            records.insertRecords(batchId, listOf(mapping.mapRecord(value)!!), now)
            records.markFailed(batchId, now, "test failed projection write")
        }

        suspend fun replay(request: ReplayRequest): ReplayJobStatusResponse {
            val start = replays.create(request, now)
            val result = withTimeout(60_000) {
                var job = replays.get(start.jobId)
                while (!job.status.terminal) {
                    delay(20)
                    job = replays.get(start.jobId)
                }
                job
            }
            assertEquals(ReplayJobStatus.Completed, result.status, result.errorMessage)
            return result
        }

        fun projectionIds(): List<Int> = projectionTables.map { number("SELECT id FROM $it") }

        fun assertCorrectedValues() {
            assertEquals(200, number("SELECT steps FROM step_samples"))
            assertEquals("deep", text("SELECT stage FROM sleep_stages"))
            assertEquals(1, number("SELECT COUNT(*) FROM sleep_stages"))
            assertEquals(90, number("SELECT sleep_score FROM sleep_summaries"))
            assertEquals(2000, number("SELECT distance_meters::int FROM activity_summaries"))
            assertEquals(120, number("SELECT systolic_mmhg FROM blood_pressure_measurements"))
            assertEquals(81, number("SELECT value::int FROM scalar_samples"))
            assertEquals(200, number("SELECT steps FROM step_daily_summaries"))
            assertEquals("2026-04-17", text("SELECT date::text FROM activity_summaries"))
            assertEquals("2026-04-17", text("SELECT start_at::date::text FROM sleep_sessions"))
            assertEquals("2026-04-17", text("SELECT start_at::date::text FROM sleep_summaries"))
            assertEquals("2026-04-17", text("SELECT measured_at::date::text FROM blood_pressure_measurements"))
        }

        fun number(sql: String): Int = text(sql).toInt()

        fun text(sql: String): String = PostgresTestDatabase.connection(config).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.next()) { "No result for $sql" }
                    rows.getString(1)
                }
            }
        }
    }
}

private val projectionTables = listOf(
    "step_samples", "sleep_sessions", "sleep_summaries", "activity_summaries", "blood_pressure_measurements", "scalar_samples",
)
