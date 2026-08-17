package me.aquitano.health.infrastructure.security

import java.security.MessageDigest

class ApiKeyHasher {
    fun hash(apiKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(apiKey.toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString(separator = "") {
            "%02x".format(
                it
            )
        }
    }
}
