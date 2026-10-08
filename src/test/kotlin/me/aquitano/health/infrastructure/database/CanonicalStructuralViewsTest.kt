package me.aquitano.health.infrastructure.database

import me.aquitano.health.infrastructure.config.DatabaseConfig
import me.aquitano.health.test.PostgresIntegrationTest
import me.aquitano.health.test.PostgresTestDatabase
import me.aquitano.health.test.execute
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Semantics of the structural canonical views (V15): per partition the top-ranked provider
 * wins via provider_ranks; DISTINCT ON families keep one row, while the window-function
 * families (sleep sessions/nights) keep ALL rows of the winning provider so naps survive.
 * Activity ranks google_health first; sleep and sleep_summary rank withings first.
 */
class CanonicalStructuralViewsTest : PostgresIntegrationTest() {
    @Test
    fun activitySummaryRankWinnerPerDate() {
        val fixture = Fixture()
        fixture.insertActivitySummary(id = 1, sourceInstanceId = WITHINGS, date = "2026-04-19")
        fixture.insertActivitySummary(id = 2, sourceInstanceId = GOOGLE, date = "2026-04-19")
        fixture.insertActivitySummary(id = 3, sourceInstanceId = WITHINGS, date = "2026-04-20")

        assertEquals(listOf(2, 3), fixture.canonicalIds("canonical_activity_summaries", "date"))
    }

    @Test
    fun sleepSummaryRankWinnerPerUtcStartDate() {
        val fixture = Fixture()
        // sleep_summary family: withings (rank 0) beats google_health
        fixture.insertSleepSummary(id = 1, sourceInstanceId = GOOGLE, startAt = "2026-04-19T22:00:00Z", endAt = "2026-04-20T06:00:00Z")
        fixture.insertSleepSummary(id = 2, sourceInstanceId = WITHINGS, startAt = "2026-04-19T22:30:00Z", endAt = "2026-04-20T06:30:00Z")
        // next UTC start date is its own partition
        fixture.insertSleepSummary(id = 3, sourceInstanceId = GOOGLE, startAt = "2026-04-20T22:00:00Z", endAt = "2026-04-21T06:00:00Z")

        assertEquals(listOf(2, 3), fixture.canonicalIds("canonical_sleep_summaries", "date"))
    }

    @Test
    fun sleepSessionsWinningProviderKeepsAllItsSessions() {
        val fixture = Fixture()
        // same UTC start date: winning withings keeps both the night and the nap, google is dropped
        fixture.insertSleepSession(id = 1, sourceInstanceId = WITHINGS, startAt = "2026-04-19T01:00:00Z", endAt = "2026-04-19T07:00:00Z")
        fixture.insertSleepSession(id = 2, sourceInstanceId = WITHINGS, startAt = "2026-04-19T14:00:00Z", endAt = "2026-04-19T14:30:00Z")
        fixture.insertSleepSession(id = 3, sourceInstanceId = GOOGLE, startAt = "2026-04-19T01:05:00Z", endAt = "2026-04-19T07:05:00Z")

        assertEquals(listOf(1, 2), fixture.canonicalIds("canonical_sleep_sessions", "start_at"))
    }

    @Test
    fun sleepSessionsSingleProviderDatePassesThrough() {
        val fixture = Fixture()
        fixture.insertSleepSession(id = 1, sourceInstanceId = GOOGLE, startAt = "2026-04-19T01:00:00Z", endAt = "2026-04-19T07:00:00Z")
        fixture.insertSleepSession(id = 2, sourceInstanceId = GOOGLE, startAt = "2026-04-19T14:00:00Z", endAt = "2026-04-19T14:30:00Z")

        assertEquals(listOf(1, 2), fixture.canonicalIds("canonical_sleep_sessions", "start_at"))
    }

    private inner class Fixture {
        val config: DatabaseConfig = PostgresTestDatabase.config()

        init {
            openDatabase(config)
            config.execute(
                """
                INSERT INTO sources (id, code, display_name, created_at)
                VALUES (1, 'withings', NULL, '2026-04-19T00:00:00Z'),
                       (2, 'google_health', NULL, '2026-04-19T00:00:00Z')
                """.trimIndent(),
            )
            config.execute(
                """
                INSERT INTO source_instances (id, source_id, provider_instance_id, display_name, created_at, updated_at)
                VALUES (1, 1, 'withings-1', NULL, '2026-04-19T00:00:00Z', '2026-04-19T00:00:00Z'),
                       (2, 2, 'google-1', NULL, '2026-04-19T00:00:00Z', '2026-04-19T00:00:00Z')
                """.trimIndent(),
            )
        }

        fun insertActivitySummary(
            id: Int,
            sourceInstanceId: Int,
            date: String,
        ) {
            config.execute(
                """
                INSERT INTO activity_summaries (id, source_instance_id, date, distance_meters, created_at)
                VALUES ($id, $sourceInstanceId, '$date', 1000.0, '2026-04-19T10:00:00Z')
                """.trimIndent(),
            )
        }

        fun insertSleepSummary(
            id: Int,
            sourceInstanceId: Int,
            startAt: String,
            endAt: String,
        ) {
            config.execute(
                """
                INSERT INTO sleep_summaries (id, source_instance_id, start_at, end_at, total_sleep_seconds, created_at)
                VALUES ($id, $sourceInstanceId, '$startAt', '$endAt', 28800, '2026-04-19T10:00:00Z')
                """.trimIndent(),
            )
        }

        fun insertSleepSession(
            id: Int,
            sourceInstanceId: Int,
            startAt: String,
            endAt: String,
        ) {
            config.execute(
                """
                INSERT INTO sleep_sessions (id, source_instance_id, start_at, end_at, duration_seconds, created_at)
                VALUES ($id, $sourceInstanceId, '$startAt', '$endAt',
                        EXTRACT(EPOCH FROM ('$endAt'::timestamptz - '$startAt'::timestamptz))::bigint,
                        '2026-04-19T10:00:00Z')
                """.trimIndent(),
            )
        }

        fun canonicalIds(
            view: String,
            orderBy: String,
        ): List<Int> {
            val ids = mutableListOf<Int>()
            PostgresTestDatabase.connection(config).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT id FROM $view ORDER BY $orderBy, id").use { resultSet ->
                        while (resultSet.next()) ids.add(resultSet.getInt(1))
                    }
                }
            }
            return ids
        }
    }

    private companion object {
        const val WITHINGS = 1
        const val GOOGLE = 2
    }
}
