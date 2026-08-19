package me.aquitano.health.application.providersync

import me.aquitano.health.api.dto.IngestionBatchRequest
import me.aquitano.health.application.IngestionService
import me.aquitano.health.domain.MetricCreatedCounts
import me.aquitano.health.domain.ProviderSyncBatch
import me.aquitano.health.domain.SyncStatus
import me.aquitano.health.infrastructure.repositories.ACCOUNT_STATUS_NEEDS_REAUTH
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
    )

    suspend fun markNeedsReauth(
        accountId: Int,
        code: String,
        message: String,
        now: Instant,
    )

    suspend fun markTokenRefreshFailed(
        accountId: Int,
        code: String,
        message: String,
        now: Instant,
    )

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

    suspend fun findExistingBatch(
        providerCode: String,
        providerInstanceId: String,
        batchExternalId: String,
        now: Instant,
    ): ExistingProviderBatch?

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
            val key = requireNotNull(tokenEncryptionKeys[it]) {
                "No token encryption key configured for provider '$it'"
            }
            TokenCipher(key)
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
        val account = providerInstanceId
            ?.let { repository.accountByProviderInstanceForStatus(providerCode, it) }
            ?: repository.accountsByProvider(providerCode)
                .firstOrNull { it.accountStatus == ACCOUNT_STATUS_NEEDS_REAUTH }
        return account?.toSyncAccount()
    }

    override suspend fun decryptAccessToken(account: SyncAccount): String =
        cipherFor(account.providerCode).decrypt(account.encryptedAccessToken)

    override suspend fun decryptRefreshToken(account: SyncAccount): String =
        cipherFor(account.providerCode).decrypt(account.encryptedRefreshToken)

    override suspend fun saveRefreshedToken(
        account: SyncAccount,
        tokens: RefreshedTokenSet,
        now: Instant,
    ) {
        val cipher = cipherFor(account.providerCode)
        repository.updateAccessToken(
            accountId = account.id,
            accessTokenCiphertext = cipher.encrypt(tokens.accessToken),
            refreshTokenCiphertext = tokens.refreshToken?.let(cipher::encrypt),
            tokenType = tokens.tokenType,
            expiresAt = tokens.expiresAt,
            scope = tokens.scope,
            now = now,
        )
    }

    override suspend fun markNeedsReauth(
        accountId: Int,
        code: String,
        message: String,
        now: Instant,
    ) {
        repository.markNeedsReauth(accountId, code, message, now)
    }

    override suspend fun markTokenRefreshFailed(
        accountId: Int,
        code: String,
        message: String,
        now: Instant,
    ) {
        repository.markTokenRefreshFailed(accountId, code, message, now)
    }

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
        repository.finishSyncRun(runId, status.stored, finishedAt, errorMessage)
    }

    override suspend fun findExistingBatch(
        providerCode: String,
        providerInstanceId: String,
        batchExternalId: String,
        now: Instant,
    ): ExistingProviderBatch? =
        ingestionService.findExistingBatch(
            provider = providerCode,
            providerInstanceId = providerInstanceId,
            batchExternalId = batchExternalId,
            now = now,
        )?.let { batch ->
            batch.status?.let { ExistingProviderBatch(batch.id, it) }
        }

    override suspend fun ingest(
        command: ProviderIngestionCommand,
        now: Instant,
    ): ProviderSyncBatch {
        val summary = ingestionService.ingestBatch(
            IngestionBatchRequest(
                provider = command.providerCode,
                providerInstanceId = command.providerInstanceId,
                batchExternalId = command.batchExternalId,
                ingestedAt = command.ingestedAt.toString(),
                sourcePayload = command.sourcePayload,
                records = command.records,
            ),
            now = now,
            // A window the provider had no data for is still stored, as an empty processed batch,
            // so the next run dedupes it instead of re-fetching the same empty day forever.
            allowEmptyRecords = true,
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
