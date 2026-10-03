package me.aquitano.health.application.providersync

import me.aquitano.health.infrastructure.time.UtcClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import me.aquitano.health.api.dto.IngestionRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import me.aquitano.health.api.dto.StepInterval
import me.aquitano.health.domain.*
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderSyncPipelineTest {
    private val now: Instant = Instant.parse("2026-04-20T10:00:00Z")
    private val request = ProviderSyncRequest(
        from = Instant.parse("2026-04-01T00:00:00Z"),
        to = Instant.parse("2026-04-02T00:00:00Z"),
        dataTypes = listOf("steps"),
    )

    @Test
    fun processedBatchCacheSkipsProviderFetch() = runBlocking {
        val store = FakeStore(
            existingBatch = ExistingProviderBatch(id = 42, status = BatchStatus.Processed),
        )
        val adapter = FakeAdapter()
        val pipeline = ProviderSyncPipeline(store, clock = UtcClock.fixed(now))

        val summary = pipeline.sync(adapter, request, now)

        assertEquals(0, adapter.fetchCalls)
        assertEquals(1, summary.batches.size)
        assertEquals(true, summary.batches.single().duplicateBatch)
        assertEquals(42, summary.batches.single().batchId)
    }

    @Test
    fun invalidRefreshTokenMarksAccountNeedsReauthBeforeStartingRun() = runBlocking {
        val store = FakeStore(
            account = syncAccount(expiresAt = now.minusSeconds(1)),
        )
        val adapter = FakeAdapter(
            refreshFailure = InvalidRefreshToken(),
        )
        val pipeline = ProviderSyncPipeline(store, clock = UtcClock.fixed(now))

        val error = assertFailsWith<ConflictException> {
            pipeline.sync(adapter, request, now)
        }

        assertEquals("fake_needs_reauth", error.code)
        assertEquals("fake_needs_reauth", store.needsReauthCode)
        assertEquals(0, store.runsStarted)
    }

    @Test
    fun unauthorizedFetchRefreshesTokenAndRetriesOnce() = runBlocking {
        val store = FakeStore()
        val adapter = FakeAdapter(throwUnauthorizedOnce = true)
        val pipeline = ProviderSyncPipeline(
            store,
            clock = UtcClock.fixed(now),
        )

        val summary = pipeline.sync(adapter, request, now)

        assertEquals(2, adapter.fetchCalls)
        assertEquals(1, adapter.refreshCalls)
        assertEquals("fresh-access", store.savedAccessToken)
        assertEquals(1, store.ingested.size)
        assertEquals("processed", summary.status)
    }

    @Test
    fun providerFetchesAreThrottledBetweenUncachedItems() = runBlocking {
        val delays = mutableListOf<Duration>()
        val adapter = FakeAdapter(
            itemCount = 2,
            providerRequestInterval = Duration.ofSeconds(5),
        )
        val pipeline = ProviderSyncPipeline(
            FakeStore(),
            throttleDelay = { delays += it },
            clock = UtcClock.fixed(now),
        )

        val summary = pipeline.sync(adapter, request, now)

        assertEquals(2, adapter.fetchCalls)
        assertEquals(2, summary.batches.size)
        assertEquals(1, delays.size)
        assertTrue(delays.single() > Duration.ZERO)
    }

    @Test
    fun reReadUnderLockSkipsRefreshWhenTokenAlreadyRotated() = runBlocking {
        // The first account read sees an expired token; the re-read inside the per-account lock sees
        // a fresh one (as if a concurrent run already refreshed and rotated the refresh token). This
        // run must NOT refresh again with the stale token — doing so is what bricks rotating-token
        // (Google) accounts into needs_reauth.
        val store = StaleThenFreshStore(
            staleAccount = syncAccount(expiresAt = now.minusSeconds(1)),
            freshAccount = syncAccount(expiresAt = now.plusSeconds(3600)),
        )
        val adapter = FakeAdapter()
        val pipeline = ProviderSyncPipeline(
            store,
            clock = UtcClock.fixed(now),
        )

        val summary = pipeline.sync(adapter, request, now)

        assertEquals(0, adapter.refreshCalls)
        assertEquals(0, store.saveCount)
        assertEquals("processed", summary.status)
    }

    @Test
    fun duplicateProviderRecordIdsCollapseBeforeIngestion() = runBlocking {
        // Ingestion rejects the whole batch over one repeated id, non-retryably, which parks the
        // sync schedule. Providers do repeat records inside a window, so the pipeline collapses
        // them instead, last one winning.
        val store = FakeStore()
        val adapter = FakeAdapter(
            records = listOf(
                stepInterval(steps = 1200),
                stepInterval(steps = 1500),
            ),
        )
        val pipeline = ProviderSyncPipeline(store, clock = UtcClock.fixed(now))

        pipeline.sync(adapter, request, now)

        val stored = store.ingested.single().records
        assertEquals(1, stored.size)
        assertEquals(1500, (stored.single() as StepInterval).steps)
    }

    @Test
    fun refreshIngestsWhenDuplicateOrderChangesTheWinningRecord() = runBlocking {
        val store = FakeStore()
        val first = stepInterval(steps = 1200)
        val last = stepInterval(steps = 1500)
        val adapter = FakeAdapter(records = listOf(first, last))
        val pipeline = ProviderSyncPipeline(store, clock = UtcClock.fixed(now))
        val refresh = request.copy(refresh = true)

        pipeline.sync(adapter, refresh, now)
        adapter.records = listOf(last, first)
        pipeline.sync(adapter, refresh, now)
        pipeline.sync(adapter, refresh, now)

        assertEquals(listOf(1500, 1200), store.ingested.map {
            (it.records.single() as StepInterval).steps
        })
    }

    @Test
    fun syncFailureSurfacesSafeMessageNotRawExceptionText() = runBlocking {
        // The raw exception text can carry internal/upstream detail (DB errors, provider response
        // bodies). It must stay in the logs; the client-facing message is the adapter's safe default.
        val secret = "jdbc:postgresql://internal-db:5432 connection refused for user aqt_admin"
        val adapter = FakeAdapter(fetchFailure = IllegalStateException(secret))
        val pipeline = ProviderSyncPipeline(
            FakeStore(),
            clock = UtcClock.fixed(now),
        )

        val error = assertFailsWith<UpstreamProviderException> {
            pipeline.sync(adapter, request, now)
        }

        assertEquals("Fake sync failed", error.message)
        assertFalse(error.message!!.contains(secret))
    }

    @Test
    fun refreshFetchesCompletedWindowsButSkipsUnchangedContent() = runBlocking {
        val store = FakeStore(existingBatch = ExistingProviderBatch(42, BatchStatus.Processed))
        val adapter = FakeAdapter()
        val pipeline = ProviderSyncPipeline(store, clock = UtcClock.fixed(now))
        repeat(2) { pipeline.sync(adapter, request.copy(refresh = true), now) }
        assertEquals(2, adapter.fetchCalls)
        assertEquals(1, store.ingested.size)
    }

    @Test
    fun openDayRefreshReusesTheSnapshotAsThePollEndAdvances() = runBlocking {
        val store = FakeStore()
        val adapter = FakeAdapter()
        val pipeline = ProviderSyncPipeline(store, clock = UtcClock.fixed(now))
        val morning = request.copy(to = Instant.parse("2026-04-01T10:00:00Z"), refresh = true)
        pipeline.sync(adapter, morning, now)
        val later = pipeline.sync(adapter, morning.copy(to = Instant.parse("2026-04-01T10:15:00Z")), now)
        assertEquals(1, store.ingested.size)
        assertTrue(later.batches.single().duplicateBatch)
        adapter.steps = 2400
        pipeline.sync(adapter, morning.copy(to = Instant.parse("2026-04-02T00:00:00Z")), now)
        assertEquals(2, store.ingested.size)
    }

    @Test
    fun sourceRecordChangesArePreservedWithoutEnvelopeNoise() = runBlocking {
        val store = FakeStore()
        val adapter = FakeAdapter()
        val pipeline = ProviderSyncPipeline(store, clock = UtcClock.fixed(now))
        val refresh = request.copy(refresh = true)
        adapter.sourceRecords = listOf(buildJsonObject { put("quality", 1); put("device", "scale") })
        pipeline.sync(adapter, refresh, now)
        adapter.sourceRecords = listOf(buildJsonObject { put("device", "scale"); put("quality", 1) })
        pipeline.sync(adapter, refresh, now)
        assertEquals(1, store.ingested.size)
        adapter.sourceRecords = listOf(buildJsonObject { put("quality", 2); put("device", "scale") })
        pipeline.sync(adapter, refresh, now)
        assertEquals(2, store.ingested.size)
    }

    @Test
    fun concurrentSyncsRotateExpiredTokenOnlyOnce() = runBlocking {
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val adapter = BlockingRefreshAdapter(refreshStarted, releaseRefresh)
        val store = FakeStore(account = syncAccount(now.minusSeconds(1)))
        val pipeline = ProviderSyncPipeline(store, clock = UtcClock.fixed(now))
        val first = async { pipeline.sync(adapter, request, now) }
        refreshStarted.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { pipeline.sync(adapter, request, now) }
        releaseRefresh.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, adapter.refreshCalls)
        assertEquals(1, store.saveCount)
        assertEquals(2, adapter.fetchCalls)
    }

    @Test
    fun rejectedTokenSaveStopsBeforeProviderFetch() = runBlocking {
        val store = RejectingSaveStore(syncAccount(now.minusSeconds(1)))
        val adapter = FakeAdapter()
        val pipeline = ProviderSyncPipeline(store, clock = UtcClock.fixed(now))
        val error = assertFailsWith<UpstreamProviderException> { pipeline.sync(adapter, request, now) }
        assertEquals("provider_account_changed", error.code)
        assertEquals(0, adapter.fetchCalls)
        assertEquals(0, store.runsStarted)
    }

    @Test
    fun refreshFailureDoesNotExposeExceptionDetails() = runBlocking {
        val adapter = FakeAdapter(refreshFailure = IllegalStateException("secret upstream credentials"))
        val store = FakeStore(account = syncAccount(now.minusSeconds(1)))
        val pipeline = ProviderSyncPipeline(store, clock = UtcClock.fixed(now))
        val error = assertFailsWith<UpstreamProviderException> { pipeline.sync(adapter, request, now) }
        assertEquals("Fake refresh failed", error.message)
        assertEquals("Fake refresh failed", store.refreshFailureMessage)
    }

    private class BlockingRefreshAdapter(
        private val started: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : FakeAdapter() {
        override suspend fun refreshAccessToken(
            refreshToken: String, account: SyncAccount, now: Instant,
        ): RefreshedTokenSet {
            started.complete(Unit)
            release.await()
            return super.refreshAccessToken(refreshToken, account, now)
        }
    }

    private class RejectingSaveStore(account: SyncAccount) : FakeStore(account = account) {
        override suspend fun saveRefreshedToken(
            account: SyncAccount, tokens: RefreshedTokenSet, now: Instant,
        ): Boolean = false
    }

    private open class FakeAdapter(
        private val refreshFailure: RuntimeException? = null,
        private var throwUnauthorizedOnce: Boolean = false,
        private val itemCount: Int = 1,
        private val fetchFailure: RuntimeException? = null,
        var records: List<IngestionRecord>? = null,
        override val providerRequestInterval: Duration = Duration.ZERO,
    ) : ProviderSyncAdapter {
        var fetchCalls = 0
        var refreshCalls = 0
        var steps = 1200
        var sourceRecords = emptyList<JsonObject>()

        override val providerCode = "fake"
        override val defaultSyncFailureMessage = "Fake sync failed"
        override val tokenRefreshFailureCode = "fake_refresh_failed"
        override val tokenRefreshFailureMessage = "Fake refresh failed"
        override val needsReauthCode = "fake_needs_reauth"
        override val needsReauthMessage = "Fake needs reconnect"

        override fun validate(request: ProviderSyncRequest): ProviderSyncPlan =
            ProviderSyncPlan(
                providerInstanceId = request.providerInstanceId,
                requestedFrom = request.from,
                requestedTo = request.to,
                items = (1..itemCount).map { index ->
                    ProviderSyncItem(
                        dataType = "steps",
                        from = request.from.plusSeconds((index - 1).toLong()),
                        to = request.to.plusSeconds((index - 1).toLong()),
                    )
                },
            )

        override fun accountUnavailable(
            providerInstanceId: String?,
            statusHint: SyncAccount?,
        ): Throwable = ConflictException("fake_not_connected", "Fake is not connected")

        override suspend fun refreshAccessToken(
            refreshToken: String,
            account: SyncAccount,
            now: Instant,
        ): RefreshedTokenSet {
            refreshCalls += 1
            refreshFailure?.let { throw it }
            return RefreshedTokenSet(
                accessToken = "fresh-access",
                refreshToken = "fresh-refresh",
                tokenType = "Bearer",
                expiresAt = now.plusSeconds(3600),
                scope = "scope",
            )
        }

        override suspend fun fetch(
            accessToken: String,
            account: SyncAccount,
            item: ProviderSyncItem,
            now: Instant,
        ): ProviderFetchedBatch {
            fetchCalls += 1
            if (throwUnauthorizedOnce) {
                throwUnauthorizedOnce = false
                throw UnauthorizedFetch()
            }
            fetchFailure?.let { throw it }
            return ProviderFetchedBatch(
                dataType = item.dataType,
                pagesFetched = 1,
                sourceRecordsReceived = 1,
                sourcePayload = buildJsonObject { put("requestId", fetchCalls) },
                sourceRecords = sourceRecords,
                records = records ?: listOf(stepInterval(steps = steps)),
            )
        }

        override fun isUnauthorized(error: Throwable): Boolean =
            error is UnauthorizedFetch

        override fun isInvalidRefreshToken(error: Throwable): Boolean =
            error is InvalidRefreshToken

        override fun batchExternalId(
            providerInstanceId: String,
            item: ProviderSyncItem,
        ): String = "fake:$providerInstanceId:${item.dataType}:${item.from}:${item.to}"

        override fun errorCode(error: Throwable): String = "fake_sync_failed"
    }

    /** In-memory [ProviderSyncStore] with counters for the interactions the tests assert on. */
    private open class FakeStore(
        private var account: SyncAccount = syncAccount(),
        private val existingBatch: ExistingProviderBatch? = null,
    ) : ProviderSyncStore {
        var needsReauthCode: String? = null
        var refreshFailureMessage: String? = null
        var savedAccessToken: String? = null
        var saveCount = 0
        var runsStarted = 0
        val ingested = mutableListOf<ProviderIngestionCommand>()

        override suspend fun selectForSync(
            providerCode: String,
            providerInstanceId: String?,
        ): SyncAccount? = account

        override suspend fun findAnyForStatusHint(
            providerCode: String,
            providerInstanceId: String?,
        ): SyncAccount? = account

        override suspend fun decryptAccessToken(account: SyncAccount): String = account.encryptedAccessToken

        override suspend fun decryptRefreshToken(account: SyncAccount): String = account.encryptedRefreshToken

        override suspend fun saveRefreshedToken(
            account: SyncAccount,
            tokens: RefreshedTokenSet,
            now: Instant,
        ): Boolean {
            saveCount += 1
            savedAccessToken = tokens.accessToken
            this.account = account.copy(
                encryptedAccessToken = tokens.accessToken,
                encryptedRefreshToken = tokens.refreshToken ?: account.encryptedRefreshToken,
                expiresAt = tokens.expiresAt,
            )
            return true
        }

        override suspend fun markNeedsReauth(
            account: SyncAccount,
            code: String,
            message: String,
            now: Instant,
        ): Boolean {
            needsReauthCode = code
            return true
        }

        override suspend fun markTokenRefreshFailed(
            account: SyncAccount,
            code: String,
            message: String,
            now: Instant,
        ): Boolean {
            refreshFailureMessage = message
            return true
        }

        override suspend fun startRun(
            providerCode: String,
            providerInstanceId: String,
            requestedFrom: Instant,
            requestedTo: Instant,
            startedAt: Instant,
        ): Int {
            runsStarted += 1
            return runsStarted
        }

        override suspend fun finishRun(
            runId: Int,
            status: SyncStatus,
            finishedAt: Instant,
            errorMessage: String?,
        ) = Unit

        override suspend fun findExistingBatch(
            providerCode: String,
            providerInstanceId: String,
            batchExternalId: String,
            now: Instant,
        ): ExistingProviderBatch? = existingBatch

        override suspend fun reusableBatchId(
            providerCode: String,
            providerInstanceId: String,
            windowKey: String,
            contentHash: String,
            now: Instant,
        ): Int? = ingested.withIndex().lastOrNull {
            it.value.providerCode == providerCode && it.value.providerInstanceId == providerInstanceId &&
                it.value.snapshot.windowKey == windowKey
        }?.takeIf { it.value.snapshot.contentHash == contentHash }?.let { it.index + 1 }

        override suspend fun ingest(
            command: ProviderIngestionCommand,
            now: Instant,
        ): ProviderSyncBatch {
            ingested += command
            return ProviderSyncBatch(
                dataType = command.dataType,
                batchId = ingested.size,
                duplicateBatch = false,
                recordsReceived = command.records.size,
                ingestionRecordsStored = command.records.size,
                metricsCreated = MetricCreatedCounts.of(StructuralMetricKinds.STEP_SAMPLES to command.records.size),
                duplicateMetricsSkipped = 0,
                affectedStepSummaryDates = listOf("2026-04-01"),
            )
        }
    }

    /** Returns a stale (expired) account on the first read and a fresh one on every read after. */
    private class StaleThenFreshStore(
        private val staleAccount: SyncAccount,
        private val freshAccount: SyncAccount,
    ) : FakeStore() {
        private var selectCalls = 0

        override suspend fun selectForSync(
            providerCode: String,
            providerInstanceId: String?,
        ): SyncAccount {
            selectCalls += 1
            return if (selectCalls == 1) staleAccount else freshAccount
        }

        override suspend fun findAnyForStatusHint(
            providerCode: String,
            providerInstanceId: String?,
        ): SyncAccount = freshAccount
    }

    private class UnauthorizedFetch : RuntimeException("unauthorized")

    private class InvalidRefreshToken : RuntimeException("invalid refresh")
}

private fun stepInterval(steps: Int): StepInterval =
    StepInterval(
        providerRecordId = "steps-1",
        startAt = "2026-04-01T08:00:00Z",
        endAt = "2026-04-01T09:00:00Z",
        steps = steps,
    )

private fun syncAccount(
    expiresAt: Instant = Instant.parse("2026-04-20T11:00:00Z"),
): SyncAccount =
    SyncAccount(
        id = 1,
        providerCode = "fake",
        providerUserId = "fake-user",
        providerInstanceId = "fake-instance",
        encryptedAccessToken = "encrypted-access",
        encryptedRefreshToken = "encrypted-refresh",
        expiresAt = expiresAt,
        accountStatus = "connected",
    )
