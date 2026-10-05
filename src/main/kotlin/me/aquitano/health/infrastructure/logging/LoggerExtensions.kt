package me.aquitano.health.infrastructure.logging

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.withLoggingContext

fun KLogger.infoWithContext(
    message: String,
    context: Map<String, Any?>,
) {
    withLoggingContext(context.mapValues { it.value?.toString() }) {
        info { context.describe(message) }
    }
}

fun KLogger.infoWithContext(
    message: String,
    vararg pairs: Pair<String, Any?>,
) {
    infoWithContext(message, pairs.toMap())
}

fun KLogger.warnWithContext(
    message: String,
    context: Map<String, Any?>,
    throwable: Throwable? = null,
) {
    withLoggingContext(context.mapValues { it.value?.toString() }) {
        warn(throwable) { context.describe(message) }
    }
}

fun KLogger.warnWithContext(
    message: String,
    vararg pairs: Pair<String, Any?>,
    throwable: Throwable? = null,
) {
    warnWithContext(message, pairs.toMap(), throwable)
}

fun KLogger.errorWithContext(
    message: String,
    context: Map<String, Any?>,
    throwable: Throwable? = null,
) {
    withLoggingContext(context.mapValues { it.value?.toString() }) {
        error(throwable) { context.describe(message) }
    }
}

fun KLogger.errorWithContext(
    message: String,
    vararg pairs: Pair<String, Any?>,
    throwable: Throwable? = null,
) {
    errorWithContext(message, pairs.toMap(), throwable)
}

private fun Map<String, Any?>.describe(message: String): String = "$message ${entries.joinToString(" ") { "${it.key}=${it.value}" }}"
