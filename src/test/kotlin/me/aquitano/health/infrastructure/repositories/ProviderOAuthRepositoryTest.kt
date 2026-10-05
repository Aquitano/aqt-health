package me.aquitano.health.infrastructure.repositories

import kotlinx.coroutines.runBlocking
import me.aquitano.health.infrastructure.security.TokenCipher
import me.aquitano.health.test.PostgresIntegrationTest
import java.time.Instant
import kotlin.test.*

class ProviderOAuthRepositoryTest : PostgresIntegrationTest() {
    private val now = Instant.parse("2026-05-15T10:00:00Z")
    private val later = Instant.parse("2026-05-15T11:00:00Z")

    @Test
    fun staleRefreshCannotUndoDisconnectOrOverwriteReconnect() =
        runBlocking {
            val repo = repository()
            val cipher = TokenCipher("test-key", PROVIDER)

            suspend fun connect() = repo.connect(accessToken = cipher.encrypt("access"), refreshToken = cipher.encrypt("same-refresh-token"))
            connect()
            val original = repo.latestAccount(PROVIDER)!!
            repo.disconnectAccount(PROVIDER, original.providerInstanceId, now)

            suspend fun assertStaleWritesRejected() {
                assertFalse(
                    repo.updateAccessToken(
                        original.id,
                        original.refreshTokenCiphertext,
                        "stale-access",
                        "stale-refresh",
                        "Bearer",
                        later,
                        "health.read",
                        later,
                    ),
                )
                assertFalse(repo.markNeedsReauth(original.id, original.refreshTokenCiphertext, "stale", "stale", later))
                assertFalse(repo.markTokenRefreshFailed(original.id, original.refreshTokenCiphertext, "stale", "stale", later))
            }
            assertStaleWritesRejected()
            assertEquals(ACCOUNT_STATUS_DISCONNECTED, repo.account().accountStatus)
            connect()
            val reconnected = repo.latestAccount(PROVIDER)!!
            assertNotEquals(original.refreshTokenCiphertext, reconnected.refreshTokenCiphertext)
            assertEquals(cipher.decrypt(original.refreshTokenCiphertext), cipher.decrypt(reconnected.refreshTokenCiphertext))
            assertStaleWritesRejected()
            assertEquals(reconnected, repo.latestAccount(PROVIDER))
        }

    @Test
    fun upsertAccountInsertsNewConnectedAccount() =
        runBlocking {
            val repo = repository()

            repo.connect()

            val account = repo.account()
            assertEquals(ACCOUNT_STATUS_CONNECTED, account.accountStatus)
            assertEquals("enc-access", account.accessTokenCiphertext)
            assertEquals("enc-refresh", account.refreshTokenCiphertext)
            assertEquals(now, account.connectedAt)
            assertNull(account.disconnectedAt)
            assertNull(account.lastTokenRefreshAt)
            assertNull(account.lastAuthErrorCode)
        }

    @Test
    fun upsertAccountReconnectsDisconnectedAccount() =
        runBlocking {
            val repo = repository()
            repo.connect()
            repo.disconnectAccount(PROVIDER, INSTANCE, now)

            repo.connect(accessToken = "new-access", refreshToken = "new-refresh", at = later)

            val account = repo.account()
            assertEquals(ACCOUNT_STATUS_CONNECTED, account.accountStatus)
            assertEquals("new-access", account.accessTokenCiphertext)
            assertEquals(later, account.connectedAt)
            assertNull(account.disconnectedAt)
            assertNull(account.lastAuthErrorCode)
        }

    @Test
    fun upsertAccountReconnectsNeedsReauthAccount() =
        runBlocking {
            val repo = repository()
            repo.connect()
            repo.markNeedsReauth(repo.account().id, "enc-refresh", "test_error", "Test error message", now)

            repo.connect(accessToken = "new-access", refreshToken = "new-refresh", at = later)

            val account = repo.account()
            assertEquals(ACCOUNT_STATUS_CONNECTED, account.accountStatus)
            assertEquals(later, account.connectedAt)
            assertNull(account.lastAuthErrorCode)
        }

    @Test
    fun disconnectAccountClearsTokensAndSetsStatus() =
        runBlocking {
            val repo = repository()
            repo.connect()

            assertTrue(repo.disconnectAccount(PROVIDER, INSTANCE, later))

            val account = repo.account()
            assertEquals(ACCOUNT_STATUS_DISCONNECTED, account.accountStatus)
            assertEquals("", account.accessTokenCiphertext)
            assertEquals("", account.refreshTokenCiphertext)
            assertEquals(later, account.disconnectedAt)
            assertNull(account.lastTokenRefreshAt)
            assertNull(account.lastTokenRefreshStatus)
            assertNull(account.lastAuthErrorCode)
            assertNull(account.lastAuthErrorMessage)
        }

    @Test
    fun disconnectAccountReturnsFalseForUnknownInstance() =
        runBlocking {
            assertFalse(repository().disconnectAccount(PROVIDER, "nonexistent", now))
        }

    @Test
    fun markNeedsReauthSetsStatusAndErrorCode() =
        runBlocking {
            val repo = repository()
            repo.connect()

            repo.markNeedsReauth(repo.account().id, "enc-refresh", "google_health_needs_reauth", "Consent was revoked", later)

            val account = repo.account()
            assertEquals(ACCOUNT_STATUS_NEEDS_REAUTH, account.accountStatus)
            assertEquals(later, account.lastTokenRefreshAt)
            assertEquals(TOKEN_REFRESH_STATUS_FAILED, account.lastTokenRefreshStatus)
            assertEquals("google_health_needs_reauth", account.lastAuthErrorCode)
            assertEquals("Consent was revoked", account.lastAuthErrorMessage)
        }

    @Test
    fun markNeedsReauthTruncatesLongErrorFields() =
        runBlocking {
            val repo = repository()
            repo.connect()

            repo.markNeedsReauth(repo.account().id, "enc-refresh", "x".repeat(300), "y".repeat(2000), later)

            val account = repo.account()
            assertEquals(200, account.lastAuthErrorCode!!.length)
            assertEquals(1000, account.lastAuthErrorMessage!!.length)
        }

    @Test
    fun markTokenRefreshFailedRecordsErrorWithoutChangingStatus() =
        runBlocking {
            val repo = repository()
            repo.connect()

            repo.markTokenRefreshFailed(repo.account().id, "enc-refresh", "transient_error", "Network timeout", later)

            val account = repo.account()
            assertEquals(ACCOUNT_STATUS_CONNECTED, account.accountStatus, "status should remain connected")
            assertEquals(later, account.lastTokenRefreshAt)
            assertEquals(TOKEN_REFRESH_STATUS_FAILED, account.lastTokenRefreshStatus)
            assertEquals("transient_error", account.lastAuthErrorCode)
            assertEquals("Network timeout", account.lastAuthErrorMessage)
        }

    @Test
    fun updateAccessTokenClearsTransientErrors() =
        runBlocking {
            val repo = repository()
            repo.connect()
            val accountId = repo.account().id
            repo.markTokenRefreshFailed(accountId, "enc-refresh", "some_error", "some message", now)

            repo.updateAccessToken(
                accountId = accountId,
                expectedRefreshTokenCiphertext = "enc-refresh",
                accessTokenCiphertext = "new-access",
                refreshTokenCiphertext = "new-refresh",
                tokenType = "Bearer",
                expiresAt = Instant.parse("2099-01-01T00:00:00Z"),
                scope = "health.read",
                now = later,
            )

            val account = repo.account()
            assertEquals(ACCOUNT_STATUS_CONNECTED, account.accountStatus)
            assertEquals("new-access", account.accessTokenCiphertext)
            assertEquals("new-refresh", account.refreshTokenCiphertext)
            assertEquals(later, account.lastTokenRefreshAt)
            assertEquals(TOKEN_REFRESH_STATUS_SUCCESS, account.lastTokenRefreshStatus)
            assertNull(account.lastAuthErrorCode)
            assertNull(account.lastAuthErrorMessage)
        }

    @Test
    fun latestAccountSkipsDisconnectedNeedsReauthAndTokenlessAccounts() =
        runBlocking {
            val repo = repository()
            repo.connect(providerUserId = "connected", providerInstanceId = "connected")
            repo.connect(providerUserId = "disconnected", providerInstanceId = "disconnected", at = later)
            repo.disconnectAccount(PROVIDER, "disconnected", later)
            repo.connect(providerUserId = "needs-reauth", providerInstanceId = "needs-reauth", at = later)
            repo.markNeedsReauth(repo.account("needs-reauth").id, "enc-refresh", "error", "msg", later)
            repo.connect(providerUserId = "tokenless", providerInstanceId = "tokenless", accessToken = "", refreshToken = "", at = later)

            assertEquals("connected", repo.latestAccount(PROVIDER)?.providerInstanceId)
        }

    @Test
    fun disconnectedAccountStaysListedButIsNoLongerSyncable() =
        runBlocking {
            val repo = repository()
            repo.connect()
            assertNotNull(repo.accountByProviderInstance(PROVIDER, INSTANCE))

            repo.disconnectAccount(PROVIDER, INSTANCE, later)

            assertNull(repo.accountByProviderInstance(PROVIDER, INSTANCE))
            assertEquals(listOf(ACCOUNT_STATUS_DISCONNECTED), repo.accountsByProvider(PROVIDER).map { it.accountStatus })
        }

    private fun repository() = ProviderOAuthRepository(openDatabase())

    private suspend fun ProviderOAuthRepository.connect(
        providerUserId: String = "user-1",
        providerInstanceId: String = INSTANCE,
        accessToken: String = "enc-access",
        refreshToken: String = "enc-refresh",
        at: Instant = now,
    ) = upsertAccount(
        providerCode = PROVIDER,
        providerUserId = providerUserId,
        providerInstanceId = providerInstanceId,
        accessTokenCiphertext = accessToken,
        refreshTokenCiphertext = refreshToken,
        tokenType = "Bearer",
        expiresAt = Instant.parse("2099-01-01T00:00:00Z"),
        scope = "health.read",
        now = at,
    )

    private suspend fun ProviderOAuthRepository.account(providerInstanceId: String = INSTANCE) = assertNotNull(accountByProviderInstanceForStatus(PROVIDER, providerInstanceId))

    private companion object {
        const val PROVIDER = "google_health"
        const val INSTANCE = "google-health-user-1"
    }
}
