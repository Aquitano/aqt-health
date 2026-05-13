package me.aquitano.external.withings

import java.time.Instant

const val WITHINGS_PROVIDER_CODE = "withings"

val WITHINGS_SCOPES = listOf(
    "user.info",
    "user.metrics",
    "user.activity",
)

data class WithingsTokenSet(
    val providerUserId: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresAt: Instant,
    val scope: String,
)

class WithingsHttpException(
    val code: String,
    message: String,
) : RuntimeException(message)
