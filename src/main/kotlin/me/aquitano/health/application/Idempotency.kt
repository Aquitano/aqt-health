package me.aquitano.health.application

import me.aquitano.health.api.dto.ProviderSyncRequest
import java.security.MessageDigest

internal fun syncRequestHash(request: ProviderSyncRequest): String =
    idempotencyRequestHash(
        request.providerInstanceId?.takeIf { it.isNotBlank() },
        request.from,
        request.to,
        request.dataTypes?.idempotencyListPart(),
        request.pageSize?.toString(),
    )

internal fun idempotencyRequestHash(vararg parts: String?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val value = part ?: ""
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(bytes)
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/**
 * Order- and duplicate-insensitive: the same key replayed with the type list reordered is the
 * same request, so it must hash the same instead of raising a spurious idempotency conflict.
 */
internal fun Iterable<String>.idempotencyListPart(): String =
    distinct().sorted().joinToString("\n")
