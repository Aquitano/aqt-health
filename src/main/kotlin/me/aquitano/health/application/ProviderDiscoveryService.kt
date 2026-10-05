package me.aquitano.health.application

import me.aquitano.health.api.dto.ProviderCatalogResponse
import me.aquitano.health.api.dto.ProviderDescriptorResponse
import me.aquitano.health.domain.HealthProviderDescriptor
import me.aquitano.health.domain.NotFoundException

class ProviderDiscoveryService(
    private val providerRegistry: HealthProviderRegistry,
) {
    fun listProviders(): ProviderCatalogResponse =
        ProviderCatalogResponse(
            items =
                providerRegistry
                    .listProviders()
                    .map { it.descriptor.toDto() },
        )

    fun getProvider(providerCode: String): ProviderDescriptorResponse =
        providerRegistry
            .getProvider(providerCode)
            ?.descriptor
            ?.toDto()
            ?: throw NotFoundException("Provider '$providerCode' not found")

    private fun HealthProviderDescriptor.toDto(): ProviderDescriptorResponse =
        ProviderDescriptorResponse(
            providerCode = providerCode,
            displayName = displayName,
            supportedDataTypes = supportedDataTypes,
            defaultDataTypes = defaultDataTypes,
            maxSyncRangeDays = maxSyncRangeDays,
            supportsPageSize = supportsPageSize,
        )
}
