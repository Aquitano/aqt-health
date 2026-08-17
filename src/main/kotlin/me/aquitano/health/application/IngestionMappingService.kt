package me.aquitano.health.application

import kotlinx.serialization.json.*
import me.aquitano.health.api.dto.*
import me.aquitano.health.domain.*

/**
 * Validates an incoming ingestion batch and maps each record DTO to its domain
 * [HealthRecord]. Per-record mapping rules live in IngestionStructuralRecordMappers
 * and IngestionScalarRecordMappers; shared field validation in IngestionMappingSupport.
 */
class IngestionMappingService {
    fun validateAndMap(request: IngestionBatchRequest): ValidatedIngestionBatch {
        val issues = mutableListOf<ValidationIssue>()
        val provider = normalizeProvider(request.provider, issues)
        val providerInstanceId = requiredNonBlank(
            request.providerInstanceId,
            "providerInstanceId",
            issues
        )
        val batchExternalId =
            optionalNonBlank(request.batchExternalId, "batchExternalId", issues)
        val ingestedAt = parseInstant(request.ingestedAt, "ingestedAt", issues)
        val sourcePayload = request.sourcePayload ?: JsonNull.also {
            issues.add(ValidationIssue("sourcePayload"))
        }
        val inputRecords = request.records
        if (inputRecords == null) {
            issues.add(ValidationIssue("records"))
        } else if (inputRecords.isEmpty()) {
            issues.add(
                ValidationIssue(
                    field = "records",
                    code = ValidationIssueCodes.InvalidState,
                    message = "must not be empty",
                )
            )
        }

        val records = inputRecords?.mapIndexedNotNull { index, dto ->
            mapRecord(index, dto, issues)
        }.orEmpty()

        val duplicateProviderIds = records
            .mapNotNull { it.providerRecordId }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        duplicateProviderIds.forEach {
            issues.add(
                ValidationIssue(
                    field = "records",
                    code = ValidationIssueCodes.InvalidState,
                    message = "providerRecordId '$it' is duplicated in this batch",
                )
            )
        }

        if (issues.isNotEmpty()) throw RequestValidationException(issues)

        return ValidatedIngestionBatch(
            provider = provider!!,
            providerInstanceId = providerInstanceId!!,
            batchExternalId = batchExternalId,
            ingestedAt = ingestedAt!!,
            sourcePayload = sourcePayload,
            records = records,
        )
    }

    /**
     * Maps a single already-stored normalized record back to a HealthRecord, for replay.
     * Returns null when the record no longer passes current validation rules.
     */
    fun mapRecord(dto: IngestionRecord): HealthRecord? =
        mapRecord(index = 0, dto = dto, issues = mutableListOf())

    private fun mapRecord(
        index: Int,
        dto: IngestionRecord,
        issues: MutableList<ValidationIssue>
    ): HealthRecord? {
        val field = "records[$index]"
        return when (dto) {
            is StepInterval -> mapStepInterval(field, dto, issues)
            is SleepSession -> mapSleepSession(field, dto, issues)
            is ActivitySummary -> mapActivitySummary(field, dto, issues)
            is SleepSummary -> mapSleepSummary(field, dto, issues)
            is BloodPressure -> mapBloodPressure(field, dto, issues)
            is ScalarSample -> mapScalarSample(field, dto, issues)
        }
    }
}
