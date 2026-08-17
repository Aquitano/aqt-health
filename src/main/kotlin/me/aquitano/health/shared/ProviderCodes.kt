package me.aquitano.health.shared

import java.util.Locale

/**
 * Persistence spelling of a provider code: trimmed, lowercase, `-` folded to `_`.
 *
 * Wire codes use hyphens (`google-health` in routes and provider descriptors) while everything
 * stored in `sources.code`, `provider_ranks`, and the sync tables uses underscores. Every boundary
 * that turns a caller-supplied or descriptor code into a stored one goes through this.
 */
fun normalizeProviderCode(code: String): String =
    code.trim().lowercase(Locale.US).replace('-', '_')
