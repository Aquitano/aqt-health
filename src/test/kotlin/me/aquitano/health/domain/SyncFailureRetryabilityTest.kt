package me.aquitano.health.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncFailureRetryabilityTest {
    @Test
    fun requestAuthAndConfigurationFailuresAreNotRetryable() {
        assertFalse(isRetryableSyncFailure(RequestValidationException(emptyList())))
        assertFalse(isRetryableSyncFailure(NotFoundException("no such account")))
        assertFalse(isRetryableSyncFailure(UnauthorizedException()))
        assertFalse(isRetryableSyncFailure(ServerConfigurationException("google_health_not_configured", "Provider is misconfigured")))
    }

    @Test
    fun conflictAndUpstreamFailuresCarryTheirOwnRetryability() {
        assertFalse(isRetryableSyncFailure(ConflictException("withings_needs_reauth", "reauthorize")))
        assertTrue(isRetryableSyncFailure(ConflictException("ingestion_batch_in_progress", "batch is processing", retryable = true)))
        assertTrue(isRetryableSyncFailure(UpstreamProviderException("withings_http_503", "service unavailable")))
        assertFalse(isRetryableSyncFailure(UpstreamProviderException("withings_needs_reauth", "reauthorize", retryable = false)))
    }

    @Test
    fun unclassifiedFailuresAreRetryable() {
        assertTrue(isRetryableSyncFailure(IllegalStateException("upstream timed out")))
    }
}
