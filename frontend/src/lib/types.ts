import type { components } from "./generated/aqtHealthApiTypes";

export type ApiSchema<Name extends keyof components["schemas"]> =
  components["schemas"][Name];

export type ApiResult<T> =
  | { ok: true; data: T }
  | { ok: false; status?: number; message: string };

export type HealthResponse = ApiSchema<"HealthResponse">;
export type StepDailySummary = ApiSchema<"StepDailySummaryResponse">;
export type StepDailySummariesResponse = ApiSchema<"StepDailySummariesResponse">;
// All scalar metrics (heart rate, HRV, respiratory rate, body, cardiovascular) share one
// wire shape; components take ScalarSample(sResponse) directly rather than per-metric aliases.
export type ScalarSample = ApiSchema<"ScalarSampleResponse">;
export type ScalarDailySummariesResponse = ApiSchema<"ScalarDailySummariesResponse">;
export type ScalarSamplesResponse = ApiSchema<"ScalarSamplesResponse">;
export type ActivitySummary = ApiSchema<"ActivitySummaryResponse">;
export type ActivitySummariesResponse = ApiSchema<"ActivitySummariesResponse">;
export type SleepSession = ApiSchema<"SleepSessionResponse">;
export type SleepSummary = ApiSchema<"SleepSummaryResponse">;
export type SleepSummariesResponse = ApiSchema<"SleepSummariesResponse">;
export type SleepNightsResponse = ApiSchema<"SleepNightsResponse">;
export type DashboardSummaryResponse = ApiSchema<"DashboardSummaryResponse">;
export type HealthDayBucket = ApiSchema<"HealthDayBucketResponse">;
export type HealthDayResponse = ApiSchema<"HealthDayResponse">;
export type IngestionBatch = ApiSchema<"IngestionBatchAdminResponse">;
export type IngestionBatchDetailResponse = ApiSchema<"IngestionBatchDetailResponse">;
export type ProviderCatalogResponse = ApiSchema<"ProviderCatalogResponse">;
export type ProviderDescriptor = ApiSchema<"ProviderDescriptorResponse">;
export type ProviderOAuthStartResponse = ApiSchema<"ProviderOAuthStartResponse">;
export type ProviderSyncRequest = ApiSchema<"ProviderSyncRequest">;
export type ProviderSyncResponse = ApiSchema<"ProviderSyncResponse">;
export type ProviderSyncJobStatusResponse = ApiSchema<"ProviderSyncJobStatusResponse">;
export type ProviderSyncJobStartResponse = ApiSchema<"ProviderSyncJobStartResponse">;
export type ProviderStatusCatalogResponse = ApiSchema<"ProviderStatusCatalogResponse">;
export type ProviderStatus = ApiSchema<"ProviderStatusResponse">;
export type ProviderAccountStatus = ApiSchema<"ProviderAccountStatusResponse">;
export type ScheduledSyncConfigUpdateRequest = ApiSchema<"ScheduledSyncConfigUpdateRequest">;
export type ScheduledSyncConfig = ApiSchema<"ScheduledSyncConfigResponse">;
export type ScheduledSyncRunResponse = ApiSchema<"ScheduledSyncRunResponse">;
export type DashboardTrendsResponse = ApiSchema<"DashboardTrendsResponse">;

export type BloodPressureMeasurement = ApiSchema<"BloodPressureMeasurementResponse">;
export type BloodPressureMeasurementsResponse = ApiSchema<"BloodPressureMeasurementsResponse">;

export type HealthDayModuleName = HealthDayResponse["modules"][number];
