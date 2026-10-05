package me.aquitano.health.application.providersync

import me.aquitano.health.api.dto.IngestionBatchRequest
import me.aquitano.health.application.IngestionService
import me.aquitano.health.domain.MetricCreatedCounts
import me.aquitano.health.domain.ProviderAccountStatus
import me.aquitano.health.domain.ProviderSyncBatch
import me.aquitano.health.domain.SyncStatus
import me.aquitano.health.infrastructure.repositories.ProviderOAuthAccount
import me.aquitano.health.infrastructure.repositories.ProviderOAuthRepository
import me.aquitano.health.infrastructure.security.TokenCipher
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistence seam for [ProviderSyncPipeline]: OAuth account/token access, sync-run bookkeeping,
 * and batch ingestion. [OAuthProviderSyncStore] is the only production implementation; the
 * interface exists so pipeline unit tests can fake persistence without a database.
 */
interface ProviderSyncStore {
    suspend fun selectForSync(
        providerCode: String,
        providerInstanceId: String?,
    ): SyncAccount?

    suspend fun findAnyForStatusHint(
        providerCode: String,
        providerInstanceId: String?,
    ): SyncAccount?

    suspend fun decryptAccessToken(account: SyncAccount): String

    suspend fun decryptRefreshToken(account: SyncAccount): String

    suspend fun saveRefreshedToken(
        account: SyncAccount,
        tokens: RefreshedTokenSet,
        now: Instant,
    ): Boolean

    suspend fun markNeedsReauth(
        account: SyncAccount,
        code: String,
        message: String,
        now: Instant,
    ): Boolean

    suspend fun markTokenRefreshFailed(
        account: SyncAccount,
        code: String,
        message: String,
        now: Instant,
    ): Boolean

    suspend fun startRun(
        providerCode: String,
        providerInstanceId: String,
        requestedFrom: Instant,
        requestedTo: Instant,
        startedAt: Instant,
    ): Int

    suspend fun finishRun(
        runId: Int,
        status: SyncStatus,
        finishedAt: Instant,
        errorMessage: String?,
    )

    /** Resolves, creating it on first use, the source instance an account's batches are stored under. */
    suspend fun sourceInstanceId(
        providerCode: String,
        providerInstanceId: String,
        now: Instant,
    ): Int

    suspend fun findExistingBatch(
        sourceInstanceId: Int,
        batchExternalId: String,
    ): ExistingProviderBatch?

    /** The processed batch whose snapshot still matches [contentHash] for this window, if any. */
    suspend fun reusableBatchId(
        sourceInstanceId: Int,
        windowKey: String,
        contentHash: String,
    ): Int?

    suspend fun ingest(
        command: ProviderIngestionCommand,
        now: Instant,
    ): ProviderSyncBatch
}

/**
 * Production [ProviderSyncStore] over [ProviderOAuthRepository] and [IngestionService]. Token
 * ciphers are created lazily per provider code from [tokenEncryptionKeys], so one store instance
 * serves every provider.
 */
class OAuthProviderSyncStore(
    private val repository: ProviderOAuthRepository,
    private val ingestionService: IngestionService,
    private val tokenEncryptionKeys: Map<String, String>,
) : ProviderSyncStore {
    private val ciphers = ConcurrentHashMap<String, TokenCipher>()

    private fun cipherFor(providerCode: String): TokenCipher =
        ciphers.computeIfAbsent(providerCode) {
            val key =
                requireNotNull(tokenEncryptionKeys[it]) {
                    "No token encryption key configured for provider '$it'"
                }
            TokenCipher(key, it)
        }

    override suspend fun selectForSync(
        providerCode: String,
        providerInstanceId: String?,
    ): SyncAccount? =
        if (providerInstanceId == null) {
            repository.latestAccount(providerCode)
        } else {
            repository.accountByProviderInstance(providerCode, providerInstanceId)
        }?.toSyncAccount()

    override suspend fun findAnyForStatusHint(
        providerCode: String,
        providerInstanceId: String?,
    ): SyncAccount? {
        val account =
            providerInstanceId
                ?.let { repository.accountByProviderInstanceForStatus(providerCode, it) }
                ?: repository
                    .accountsByProvider(providerCode)
                    .firstOrNull { it.accountStatus == ProviderAccountStatus.NeedsReauth }
        return account?.toSyncAccount()
    }

    override suspend fun decryptAccessToken(account: SyncAccount): String = cipherFor(account.providerCode).decrypt(account.encryptedAccessToken)

    override suspend fun decryptRefreshToken(account: SyncAccount): String = cipherFor(account.providerCode).decrypt(account.encryptedRefreshToken)

    override suspend fun saveRefreshedToken(
        account: SyncAccount,
        tokens: RefreshedTokenSet,
        now: Instant,
    ): Boolean {
        val cipher = cipherFor(account.providerCode)
        return repository.updateAccessToken(
            accountId = account.id,
            expectedRefreshTokenCiphertext = account.encryptedRefreshToken,
            accessTokenCiphertext = cipher.encrypt(tokens.accessToken),
            refreshTokenCiphertext = tokens.refreshToken?.let(cipher::encrypt),
            tokenType = tokens.tokenType,
            expiresAt = tokens.expiresAt,
            scope = tokens.scope,
            now = now,
        )
    }

    override suspend fun markNeedsReauth(
        account: SyncAccount,
        code: String,
        message: String,
        now: Instant,
    ): Boolean = repository.markNeedsReauth(account.id, account.encryptedRefreshToken, code, message, now)

    override suspend fun markTokenRefreshFailed(
        account: SyncAccount,
        code: String,
        message: String,
        now: Instant,
    ): Boolean = repository.markTokenRefreshFailed(account.id, account.encryptedRefreshToken, code, message, now)

    override suspend fun startRun(
        providerCode: String,
        providerInstanceId: String,
        requestedFrom: Instant,
        requestedTo: Instant,
        startedAt: Instant,
    ): Int =
        repository.startSyncRun(
            providerCode = providerCode,
            providerInstanceId = providerInstanceId,
            requestedFrom = requestedFrom,
            requestedTo = requestedTo,
            startedAt = startedAt,
        )

    override suspend fun finishRun(
        runId: Int,
        status: SyncStatus,
        finishedAt: Instant,
        errorMessage: String?,
    ) {
        repository.finishSyncRun(runId, status, finishedAt, errorMessage)
    }

    override suspend fun sourceInstanceId(
        providerCode: String,
        providerInstanceId: String,
        now: Instant,
    ): Int = ingestionService.sourceInstanceId(providerCode, providerInstanceId, now)

    override suspend fun findExistingBatch(
        sourceInstanceId: Int,
        batchExternalId: String,
    ): ExistingProviderBatch? =
        ingestionService
            .findExistingBatch(sourceInstanceId, batchExternalId)
            ?.let { batch -> ExistingProviderBatch(batch.id, batch.status) }

    override suspend fun reusableBatchId(
        sourceInstanceId: Int,
        windowKey: String,
        contentHash: String,
    ): Int? = ingestionService.reusableSyncBatchId(sourceInstanceId, windowKey, contentHash)

    override suspend fun ingest(
        command: ProviderIngestionCommand,
        now: Instant,
    ): ProviderSyncBatch {
        val summary =
            ingestionService.ingestBatch(
                IngestionBatchRequest(
                    provider = command.providerCode,
                    providerInstanceId = command.providerInstanceId,
                    batchExternalId = command.batchExternalId,
                    ingestedAt = command.ingestedAt.toString(),
                    sourcePayload = command.sourcePayload,
                    records = command.records,
                ),
                now = now,
                allowEmptyRecords = true,
                snapshot = command.snapshot,
            )
        return ProviderSyncBatch(
            dataType = command.dataType,
            batchId = summary.batchId,
            duplicateBatch = summary.duplicateBatch,
            recordsReceived = summary.recordsReceived,
            ingestionRecordsStored = summary.ingestionRecordsStored,
            metricsCreated = MetricCreatedCounts(summary.metricsCreated),
            duplicateMetricsSkipped = summary.metricsSkipped.duplicates,
            affectedStepSummaryDates = summary.affectedStepSummaryDates,
        )
    }
}

private fun ProviderOAuthAccount.toSyncAccount(): SyncAccount =
    SyncAccount(
        id = id,
        providerCode = providerCode,
        providerUserId = providerUserId,
        providerInstanceId = providerInstanceId,
        encryptedAccessToken = accessTokenCiphertext,
        encryptedRefreshToken = refreshTokenCiphertext,
        expiresAt = expiresAt,
        accountStatus = accountStatus,
    )
