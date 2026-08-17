@file:OptIn(ExperimentalKtorApi::class)

package me.aquitano.health.api

import io.ktor.openapi.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer
import me.aquitano.external.withings.WITHINGS_PROVIDER_CODE
import me.aquitano.health.api.dto.*
import me.aquitano.health.domain.BatchStatus
import me.aquitano.health.domain.BodyMetricTypes
import me.aquitano.health.domain.ScalarMetricTypes
import me.aquitano.health.domain.ValidationIssueCodes
import me.aquitano.health.shared.AppJson

internal const val ExampleProviderInstanceId = "$WITHINGS_PROVIDER_CODE:123456"
internal const val ExampleRequestId = "test-request-123"

internal const val ExampleDate = "2026-04-02"
internal const val ExampleFromDate = "2026-04-01"
internal const val ExampleToDate = "2026-04-07"
internal const val ExampleFromAt = "${ExampleFromDate}T00:00:00Z"
internal const val ExampleToAt = "${ExampleDate}T00:00:00Z"
internal const val ExampleIngestedAt = "${ExampleDate}T08:15:30Z"
internal const val ExampleBatchExternalId =
    "$WITHINGS_PROVIDER_CODE-$ExampleToAt"
internal const val ExampleStepStartAt = "${ExampleDate}T07:00:00Z"
internal const val ExampleStepEndAt = "${ExampleDate}T08:00:00Z"
internal const val ExampleSleepStartAt = "${ExampleFromDate}T22:30:00Z"
internal const val ExampleSleepEndAt = "${ExampleDate}T06:45:00Z"
internal const val ExampleSleepStageStartAt = "${ExampleFromDate}T23:00:00Z"
internal const val ExampleSleepStageEndAt = "${ExampleFromDate}T23:45:00Z"
internal const val ExampleBodyMeasuredAt = "${ExampleDate}T06:50:00Z"
internal const val ExampleHeartRateMeasuredAt = "${ExampleDate}T08:05:00Z"
internal fun ingestionBatchExample(): ExampleObject =
    jsonExample(
        summary = "Batch with normalized records",
        value = IngestionBatchRequest(
            provider = WITHINGS_PROVIDER_CODE,
            providerInstanceId = ExampleProviderInstanceId,
            batchExternalId = ExampleBatchExternalId,
            ingestedAt = ExampleIngestedAt,
            sourcePayload = buildJsonObject {
                put("job", JsonPrimitive("daily-sync"))
            },
            records = listOf(
                StepInterval(
                    providerRecordId = "steps-1",
                    startAt = ExampleStepStartAt,
                    endAt = ExampleStepEndAt,
                    steps = 1200,
                ),
                SleepSession(
                    providerRecordId = "sleep-1",
                    startAt = ExampleSleepStartAt,
                    endAt = ExampleSleepEndAt,
                    stages = listOf(
                        SleepStage(
                            stage = "deep",
                            startAt = ExampleSleepStageStartAt,
                            endAt = ExampleSleepStageEndAt,
                        )
                    ),
                ),
                ScalarSample(
                    providerRecordId = "weight-1",
                    measuredAt = ExampleBodyMeasuredAt,
                    metricType = BodyMetricTypes.WEIGHT,
                    value = 78.4,
                    unit = "kg",
                ),
                ScalarSample(
                    providerRecordId = "hr-1",
                    measuredAt = ExampleHeartRateMeasuredAt,
                    metricType = ScalarMetricTypes.HEART_RATE,
                    value = 62.0,
                    unit = "bpm",
                    context = "resting",
                ),
            ),
        ),
    )

internal fun ingestionSummaryExample(duplicate: Boolean = false): ExampleObject =
    jsonExample(
        summary = if (duplicate) "Duplicate batch" else "Created batch",
        value = IngestionSummaryResponse(
            batchId = 42,
            status = BatchStatus.Processed,
            duplicateBatch = duplicate,
            recordsReceived = 4,
            ingestionRecordsStored = if (duplicate) 0 else 4,
            metricsCreated = if (duplicate) {
                emptyMap()
            } else {
                mapOf(
                    "step_samples" to 1,
                    "sleep_sessions" to 1,
                    "sleep_stages" to 1,
                    "heart_rate" to 1,
                )
            },
            metricsSkipped = MetricSkippedCountsResponse(
                duplicates = if (duplicate) 4 else 0,
            ),
            affectedStepSummaryDates = listOf(ExampleDate),
        ),
    )

internal fun providerSyncRequestExample(): ExampleObject =
    jsonExample(
        summary = "Provider sync request",
        value = ProviderSyncRequest(
            providerInstanceId = ExampleProviderInstanceId,
            from = ExampleFromAt,
            to = ExampleToAt,
            dataTypes = listOf("activity", "measures"),
            pageSize = 100,
        ),
    )

internal fun healthResponseExample(): ExampleObject =
    jsonExample(
        summary = "Health status",
        value = HealthResponse(
            status = "ok",
            service = "aqt-health",
            time = ExampleIngestedAt,
        ),
    )

internal fun validationErrorExample(): ExampleObject =
    jsonExample(
        summary = "Validation failed",
        value = ErrorResponse(
            ErrorBody(
                code = "validation_failed",
                message = "Request validation failed",
                requestId = ExampleRequestId,
                details = listOf(
                    ErrorDetail(
                        field = "fromDate",
                        code = ValidationIssueCodes.InvalidFormat,
                        message = "must be an ISO-8601 date",
                    ),
                    ErrorDetail(
                        field = "toDate",
                        code = ValidationIssueCodes.InvalidRange,
                        message = "must be on or after fromDate",
                    ),
                ),
            )
        ),
    )

private fun errorExample(summary: String, code: String, message: String): ExampleObject =
    jsonExample(
        summary = summary,
        value = ErrorResponse(
            ErrorBody(
                code = code,
                message = message,
                requestId = ExampleRequestId,
            )
        ),
    )

internal fun unauthorizedErrorExample(): ExampleObject =
    errorExample("Unauthorized", "unauthorized", "Missing or invalid API key")

internal fun notFoundErrorExample(): ExampleObject =
    errorExample("Not found", "not_found", "Provider '$WITHINGS_PROVIDER_CODE' not found")

internal fun conflictErrorExample(): ExampleObject =
    errorExample(
        "Conflict",
        "ingestion_batch_in_progress",
        "Batch '$ExampleBatchExternalId' already exists with status 'accepted'",
    )

internal fun upstreamErrorExample(): ExampleObject =
    errorExample("Upstream provider failure", "upstream_unavailable", "Provider request failed")

internal fun internalErrorExample(): ExampleObject =
    errorExample("Internal server error", "internal_error", "Unexpected server error")

private inline fun <reified T> jsonExample(
    summary: String,
    value: T
): ExampleObject =
    ExampleObject(
        summary = summary,
        value = GenericElement(
            AppJson.encodeToJsonElement(
                serializer<T>(),
                value
            )
        ),
    )
