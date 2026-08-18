package me.aquitano.health.application

import me.aquitano.health.domain.HealthProvider
import me.aquitano.health.shared.normalizeProviderCode

class HealthProviderRegistry(
    providers: List<HealthProvider>
) {
    private val providerMap: Map<String, HealthProvider> = buildMap {
        providers.forEach { provider ->
            put(normalizeProviderCode(provider.providerCode), provider)
            put(normalizeProviderCode(provider.descriptor.providerCode), provider)
            provider.descriptor.aliases.forEach { alias ->
                put(normalizeProviderCode(alias), provider)
            }
        }
    }

    private val sortedProviders =
        providers.sortedBy { it.descriptor.providerCode }

    fun getProvider(code: String): HealthProvider? =
        providerMap[normalizeProviderCode(code)]

    fun listProviders(): List<HealthProvider> = sortedProviders
}
