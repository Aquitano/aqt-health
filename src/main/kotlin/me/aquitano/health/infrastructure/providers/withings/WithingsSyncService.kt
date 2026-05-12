package me.aquitano.health.infrastructure.providers.withings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.aquitano.health.api.dto.*
import me.aquitano.health.application.IngestionService
import me.aquitano.health.domain.ConflictException
import me.aquitano.health.domain.RequestValidationException
import me.aquitano.health.domain.UpstreamProviderException
import me.aquitano.health.domain.ValidationIssue
import me.aquitano.health.infrastructure.config.WithingsConfig
import me.aquitano.health.infrastructure.repositories.ProviderOAuthAccount
import me.aquitano.health.infrastructure.repositories.ProviderOAuthRepository
import me.aquitano.health.infrastructure.security.TokenCipher
import java.time.Duration
import java.time.Instant

class WithingsSyncService(
    private val config: WithingsConfig,
    private val repository: ProviderOAuthRepository,
    private val client: WithingsClient,
    private val normalizer: WithingsNormalizer,
    private val ingestionService: IngestionService,
) {
    suspend fun sync(request: WithingsSyncRequest, now: Instant): WithingsSyncResponse {
        val validated = validate(request, now)
        val account = repository.latestAccount(WITHINGS_PROVIDER_CODE)
            ?: throw ConflictException("withings_not_connected", "Withings is not connected")
        val cipher = TokenCipher(config.tokenEncryptionKey)
        val tokenState = freshAccessToken(account, cipher, now)
        val runId = repository.startSyncRun(
            providerCode = WITHINGS_PROVIDER_CODE,
            providerInstanceId = account.providerInstanceId,
            requestedFrom = validated.from,
            requestedTo = validated.to,
            startedAt = now,
        )

        val batches = mutableListOf<GoogleHealthSyncBatchResponse>()
        val errors = mutableListOf<GoogleHealthSyncErrorResponse>()
        for (dataType in validated.dataTypes) {
            try {
                syncDataType(dataType, validated, tokenState, account, cipher, now)?.let(batches::add)
            } catch (throwable: Throwable) {
                errors.add(
                    GoogleHealthSyncErrorResponse(
                        dataType = dataType,
                        code = errorCode(throwable),
                        message = throwable.message ?: "Withings sync failed",
                    )
                )
            }
        }

        val status = when {
            errors.isEmpty() -> "processed"
            batches.isEmpty() -> "failed"
            else -> "partial_failed"
        }
        repository.finishSyncRun(
            runId = runId,
            status = status,
            finishedAt = now,
            errorMessage = errors.joinToString("; ") { "${it.dataType}: ${it.message}" }.ifBlank { null },
        )
        if (batches.isEmpty() && errors.isNotEmpty()) {
            val first = errors.first()
            throw UpstreamProviderException(first.code, first.message, 502)
        }

        return WithingsSyncResponse(
            provider = WITHINGS_PROVIDER_CODE,
            providerInstanceId = account.providerInstanceId,
            requestedRange = GoogleHealthRequestedRangeResponse(validated.from.toString(), validated.to.toString()),
            batches = batches,
            errors = errors,
        )
    }

    private suspend fun syncDataType(
        dataType: String,
        validated: ValidatedSyncRequest,
        tokenState: TokenState,
        account: ProviderOAuthAccount,
        cipher: TokenCipher,
        now: Instant,
    ): GoogleHealthSyncBatchResponse? {
        val fetchResult = fetchWithOneAuthRetry(
            tokenState,
            account,
            cipher,
            dataType,
            validated.from,
            validated.to,
            now,
        )
        val normalized = normalizer.normalize(fetchResult)
        if (normalized.records.isEmpty()) return null

        val summary = ingestionService.ingestBatch(
            IngestionBatchRequest(
                provider = WITHINGS_PROVIDER_CODE,
                providerInstanceId = account.providerInstanceId,
                batchExternalId = batchExternalId(account.providerInstanceId, dataType, validated),
                ingestedAt = now.toString(),
                sourcePayload = sourcePayload(account.providerInstanceId, dataType, validated, normalized),
                records = normalized.records,
            ),
            now = now,
        )

        return GoogleHealthSyncBatchResponse(
            dataType = dataType,
            batchId = summary.batchId,
            duplicateBatch = summary.duplicateBatch,
            recordsReceived = summary.recordsReceived,
            ingestionRecordsStored = summary.ingestionRecordsStored,
            metricsCreated = summary.metricsCreated,
            metricsSkipped = summary.metricsSkipped,
            affectedStepSummaryDates = summary.affectedStepSummaryDates,
        )
    }

    private fun batchExternalId(providerInstanceId: String, dataType: String, request: ValidatedSyncRequest): String =
        "withings:$providerInstanceId:$dataType:${request.from}:${request.to}"

    private fun sourcePayload(
        providerInstanceId: String,
        dataType: String,
        request: ValidatedSyncRequest,
        normalized: WithingsNormalizedBatch,
    ) = buildJsonObject {
        put("provider", WITHINGS_PROVIDER_CODE)
        put("providerInstanceId", providerInstanceId)
        put("requestedFrom", request.from.toString())
        put("requestedTo", request.to.toString())
        put("dataType", dataType)
        put("pages", normalized.sourcePayload["pages"] ?: JsonArray(emptyList()))
    }

    private suspend fun freshAccessToken(account: ProviderOAuthAccount, cipher: TokenCipher, now: Instant): TokenState {
        val accessToken = cipher.decrypt(account.accessTokenCiphertext)
        val refreshToken = cipher.decrypt(account.refreshTokenCiphertext)
        if (account.expiresAt.isAfter(now.plusSeconds(60))) return TokenState(accessToken, refreshToken)
        return refreshAccessToken(account, refreshToken, cipher, now)
    }

    private suspend fun fetchWithOneAuthRetry(
        tokenState: TokenState,
        account: ProviderOAuthAccount,
        cipher: TokenCipher,
        dataType: String,
        from: Instant,
        to: Instant,
        now: Instant,
    ): WithingsFetchResult =
        try {
            client.fetch(dataType, tokenState.accessToken, from, to)
        } catch (_: WithingsUnauthorizedException) {
            val refreshed = refreshAccessToken(account, tokenState.refreshToken, cipher, now)
            client.fetch(dataType, refreshed.accessToken, from, to)
        }

    private suspend fun refreshAccessToken(
        account: ProviderOAuthAccount,
        refreshToken: String,
        cipher: TokenCipher,
        now: Instant,
    ): TokenState {
        val tokens = try {
            client.refreshToken(refreshToken, now)
        } catch (throwable: Throwable) {
            throw UpstreamProviderException(
                "withings_token_refresh_failed",
                throwable.message ?: "Withings OAuth token refresh failed",
                502,
            )
        }
        val nextRefreshToken = tokens.refreshToken ?: refreshToken
        repository.updateAccessToken(
            accountId = account.id,
            accessTokenCiphertext = cipher.encrypt(tokens.accessToken),
            refreshTokenCiphertext = tokens.refreshToken?.let(cipher::encrypt),
            tokenType = tokens.tokenType,
            expiresAt = tokens.expiresAt,
            scope = tokens.scope,
            now = now,
        )
        return TokenState(tokens.accessToken, nextRefreshToken)
    }

    private fun validate(request: WithingsSyncRequest, now: Instant): ValidatedSyncRequest {
        val issues = mutableListOf<ValidationIssue>()
        val from = request.from?.let { parseInstant("from", it, issues) }
        val to = request.to?.let { parseInstant("to", it, issues) }
        val resolvedFrom: Instant
        val resolvedTo: Instant
        if (from == null && to == null && issues.isEmpty()) {
            resolvedTo = now
            resolvedFrom = now.minus(Duration.ofDays(7))
        } else {
            resolvedFrom = from ?: run {
                issues.add(ValidationIssue("from", "is required when to is provided"))
                now
            }
            resolvedTo = to ?: run {
                issues.add(ValidationIssue("to", "is required when from is provided"))
                now
            }
        }
        if (!resolvedFrom.isBefore(resolvedTo)) issues.add(ValidationIssue("from", "must be before to"))
        if (Duration.between(resolvedFrom, resolvedTo) > Duration.ofDays(31)) {
            issues.add(ValidationIssue("to", "range must not exceed 31 days"))
        }
        val dataTypes = request.dataTypes?.takeIf { it.isNotEmpty() } ?: WITHINGS_DEFAULT_DATA_TYPES
        dataTypes.forEachIndexed { index, dataType ->
            if (dataType !in WITHINGS_DEFAULT_DATA_TYPES) {
                issues.add(ValidationIssue("dataTypes[$index]", "unsupported Withings data type"))
            }
        }
        if (issues.isNotEmpty()) throw RequestValidationException(issues)
        return ValidatedSyncRequest(resolvedFrom, resolvedTo, dataTypes.distinct())
    }

    private fun parseInstant(field: String, value: String, issues: MutableList<ValidationIssue>): Instant? =
        runCatching { Instant.parse(value) }.getOrElse {
            issues.add(ValidationIssue(field, "must be an ISO-8601 instant"))
            null
        }

    private fun errorCode(throwable: Throwable): String =
        when (throwable) {
            is WithingsHttpException -> throwable.code
            is UpstreamProviderException -> throwable.code
            else -> "withings_sync_failed"
        }

    private data class ValidatedSyncRequest(
        val from: Instant,
        val to: Instant,
        val dataTypes: List<String>,
    )

    private data class TokenState(
        val accessToken: String,
        val refreshToken: String,
    )
}
