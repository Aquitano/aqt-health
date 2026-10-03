package me.aquitano.health.application

import io.github.oshai.kotlinlogging.KotlinLogging
import me.aquitano.health.api.dto.*
import me.aquitano.health.api.dto.ProviderSyncRequest
import me.aquitano.health.domain.*
import me.aquitano.health.domain.ProviderSyncProgressSink
import me.aquitano.health.infrastructure.logging.*
import me.aquitano.health.infrastructure.repositories.ProviderOAuthRepository
import me.aquitano.health.infrastructure.repositories.ProviderOAuthStateConsumeResult
import me.aquitano.health.infrastructure.repositories.ScheduledSyncRepository
import me.aquitano.health.shared.normalizeProviderCode
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.*
import me.aquitano.health.domain.ProviderSyncRequest as DomainProviderSyncRequest

private val logger = KotlinLogging.logger {}

class ProviderWorkflowService(
    private val providerRegistry: HealthProviderRegistry,
    private val providerOAuthRepository: ProviderOAuthRepository,
    private val providerStatusService: ProviderStatusService,
    private val scheduledSyncRepository: ScheduledSyncRepository,
) {
    private val random = SecureRandom()

    suspend fun startOAuth(
        providerCode: String,
        now: Instant,
    ): ProviderOAuthStartResponse {
        val provider =
            providerRegistry.getProvider(providerCode)
                ?: throw NotFoundException("Provider '$providerCode' not found")

        val state = randomState()
        val expiresAt = now.plus(Duration.ofMinutes(10))
        val authorizationUrl = provider.getAuthUrl(state)
        providerOAuthRepository.insertState(
            state,
            provider.providerCode,
            now,
            expiresAt,
        )
        logger.infoWithContext(
            "provider_oauth_start_created",
            "provider" to provider.providerCode,
            "expiresAt" to expiresAt,
        )

        return ProviderOAuthStartResponse(
            provider = provider.providerCode,
            authorizationUrl = authorizationUrl,
            expiresAt = expiresAt.toString(),
        )
    }

    suspend fun completeOAuth(
        providerCode: String,
        code: String?,
        state: String?,
        error: String?,
        now: Instant,
    ): ProviderOAuthCallbackResponse {
        val provider =
            providerRegistry.getProvider(providerCode)
                ?: throw NotFoundException("Provider '$providerCode' not found")

        if (!error.isNullOrBlank()) {
            logger.warnWithContext(
                "provider_oauth_callback_rejected",
                "provider" to provider.providerCode,
                "error" to error,
            )
            throw RequestValidationException(
                listOf(
                    ValidationIssue(
                        field = "error",
                        code = ValidationIssueCodes.InvalidState,
                        message = error,
                    ),
                ),
            )
        }

        val authCode = code?.takeIf { it.isNotBlank() }
        val authState = state?.takeIf { it.isNotBlank() }
        if (authCode == null || authState == null) {
            throw RequestValidationException(
                listOf(
                    ValidationIssue("code"),
                    ValidationIssue("state"),
                ),
            )
        }

        val stateError =
            when (providerOAuthRepository.consumeState(authState, provider.providerCode, now)) {
                ProviderOAuthStateConsumeResult.Consumed -> null
                ProviderOAuthStateConsumeResult.AlreadyUsed -> "was already used"
                ProviderOAuthStateConsumeResult.Expired -> "has expired"
                ProviderOAuthStateConsumeResult.NotFound -> "is invalid"
            }
        if (stateError != null) {
            throw RequestValidationException(
                listOf(ValidationIssue("state", ValidationIssueCodes.InvalidState, stateError)),
            )
        }

        val connection = provider.connect(authCode, now)
        scheduledSyncRepository.resumeParked(provider.providerCode, connection.providerInstanceId, now)
        return ProviderOAuthCallbackResponse(
            provider = connection.providerCode,
            providerInstanceId = connection.providerInstanceId,
            connected = connection.connected,
        )
    }

    suspend fun sync(
        providerCode: String,
        request: DomainProviderSyncRequest,
        now: Instant,
        progress: ProviderSyncProgressSink,
    ): ProviderSyncResponse =
        providerRegistry
            .getProvider(providerCode)
            ?.sync(request, now, progress)
            ?.toDto()
            ?: throw NotFoundException("Provider '$providerCode' not found")

    suspend fun listAccounts(
        providerCode: String,
        now: Instant,
    ): ProviderAccountListResponse {
        val provider =
            providerRegistry.getProvider(providerCode)
                ?: throw NotFoundException("Provider '$providerCode' not found")
        return ProviderAccountListResponse(
            provider = provider.descriptor.providerCode,
            accounts = providerStatusService.listAccountStatuses(providerCode, now),
        )
    }

    suspend fun getAccount(
        providerCode: String,
        providerInstanceId: String,
        now: Instant,
    ): ProviderAccountStatusResponse = providerStatusService.getAccountStatus(providerCode, providerInstanceId, now)

    suspend fun disconnect(
        providerCode: String,
        providerInstanceId: String,
        now: Instant,
    ): ProviderDisconnectResponse {
        val provider =
            providerRegistry.getProvider(providerCode)
                ?: throw NotFoundException("Provider '$providerCode' not found")
        val normalizedCode = normalizeProviderCode(providerCode)
        providerOAuthRepository.accountByProviderInstanceForStatus(
            providerCode = normalizedCode,
            providerInstanceId = providerInstanceId,
        ) ?: throw NotFoundException("Provider account '$providerInstanceId' not found")
        providerOAuthRepository.disconnectAccount(
            providerCode = normalizedCode,
            providerInstanceId = providerInstanceId,
            now = now,
        )
        return ProviderDisconnectResponse(
            provider = provider.descriptor.providerCode,
            providerInstanceId = providerInstanceId,
            disconnected = true,
            status = ProviderAccountLifecycleStatus.Disconnected,
        )
    }

    suspend fun reconnect(
        providerCode: String,
        providerInstanceId: String,
        now: Instant,
    ): ProviderOAuthStartResponse {
        providerRegistry.getProvider(providerCode)
            ?: throw NotFoundException("Provider '$providerCode' not found")
        val normalizedCode = normalizeProviderCode(providerCode)
        providerOAuthRepository.accountByProviderInstanceForStatus(
            providerCode = normalizedCode,
            providerInstanceId = providerInstanceId,
        ) ?: throw NotFoundException("Provider account '$providerInstanceId' not found")
        return startOAuth(providerCode, now)
    }

    private fun randomState(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * Ceiling on the range of a single manual sync or sync job. Providers are fetched in throttled
 * daily windows, so an unbounded range (`from=1970-01-01`) expands into tens of thousands of
 * upstream requests inside one job. Longer histories are still reachable, one request at a time.
 */
val MAX_PROVIDER_SYNC_RANGE: Duration = Duration.ofDays(1095)

internal fun ProviderSyncRequest.toDomain(now: Instant): DomainProviderSyncRequest {
    val issues = mutableListOf<ValidationIssue>()
    val parsedFrom = from?.let { parseInstant(it, "from", issues) }
    val parsedTo = to?.let { parseInstant(it, "to", issues) }

    val resolvedFrom: Instant
    val resolvedTo: Instant
    if (parsedFrom == null && parsedTo == null && issues.isEmpty()) {
        resolvedTo = now
        resolvedFrom = now.minus(Duration.ofDays(7))
    } else {
        resolvedFrom = parsedFrom ?: run {
            issues.add(
                ValidationIssue(
                    field = "from",
                    code = ValidationIssueCodes.Required,
                    message = "is required when to is provided",
                ),
            )
            now
        }
        resolvedTo = parsedTo ?: run {
            issues.add(
                ValidationIssue(
                    field = "to",
                    code = ValidationIssueCodes.Required,
                    message = "is required when from is provided",
                ),
            )
            now
        }
    }

    if (!resolvedFrom.isBefore(resolvedTo)) {
        issues.add(
            ValidationIssue(
                field = "from",
                code = ValidationIssueCodes.InvalidRange,
                message = "must be before to",
            ),
        )
    } else if (Duration.between(resolvedFrom, resolvedTo) > MAX_PROVIDER_SYNC_RANGE) {
        issues.add(
            ValidationIssue(
                field = "from",
                code = ValidationIssueCodes.InvalidRange,
                message = "range must not exceed ${MAX_PROVIDER_SYNC_RANGE.toDays()} days",
            ),
        )
    }
    if (pageSize != null && pageSize <= 0) {
        issues.add(
            ValidationIssue(
                field = "pageSize",
                code = ValidationIssueCodes.OutOfRange,
                message = "must be greater than 0",
            ),
        )
    }
    if (providerInstanceId != null && providerInstanceId.isNotBlank() && providerInstanceId.trim() != providerInstanceId) {
        issues.add(
            ValidationIssue(
                field = "providerInstanceId",
                code = ValidationIssueCodes.InvalidFormat,
                message = "must not have leading or trailing whitespace",
            ),
        )
    }

    if (issues.isNotEmpty()) throw RequestValidationException(issues)
    return DomainProviderSyncRequest(
        providerInstanceId = providerInstanceId?.takeIf { it.isNotBlank() },
        from = resolvedFrom,
        to = resolvedTo,
        dataTypes = dataTypes?.distinct(),
        pageSize = pageSize,
    )
}
