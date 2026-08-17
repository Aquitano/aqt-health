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
export type IngestionBatchesResponse = ApiSchema<"IngestionBatchesResponse">;
export type IngestionBatchDetailResponse = ApiSchema<"IngestionBatchDetailResponse">;
export type ProviderCatalogResponse = ApiSchema<"ProviderCatalogResponse">;
export type ProviderDescriptor = ApiSchema<"ProviderDescriptorResponse">;
export type ProviderOAuthStartResponse = ApiSchema<"ProviderOAuthStartResponse">;
export type ProviderSyncRequest = ApiSchema<"ProviderSyncRequest">;
export type ProviderSyncResponse = ApiSchema<"ProviderSyncResponse">;
export type ProviderSyncJobStatusResponse = ApiSchema<"ProviderSyncJobStatusResponse">;
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
export type HealthStatusData = {
  apiBaseUrl: string;
  health: ApiResult<HealthResponse>;
};

export type HeartRateDailyPoint = {
  date: string;
  count: number;
  avg: number | null;
  min: number | null;
  max: number | null;
};

export type HealthDataPageData = HealthStatusData & {
  summary: ApiResult<DashboardSummaryResponse>;
  trends: ApiResult<DashboardTrendsResponse>;
  healthDay: ApiResult<HealthDayResponse>;
  dailySteps: ApiResult<StepDailySummariesResponse>;
  activitySummaries: ApiResult<ActivitySummariesResponse>;
  bodyMeasurements: ApiResult<ScalarSamplesResponse>;
  latestHeartRate: ApiResult<ScalarSamplesResponse>;
  heartRateDaily: HeartRateDailyPoint[];
  sleepNights: ApiResult<SleepNightsResponse>;
  sleepSummaries: ApiResult<SleepSummariesResponse>;
  respiratoryRates: ApiResult<ScalarSamplesResponse>;
  hrvSamples: ApiResult<ScalarSamplesResponse>;
  latestActivity: ApiResult<ActivitySummariesResponse>;
  latestSleepSummary: ApiResult<SleepSummariesResponse>;
  latestRespiratoryRate: ApiResult<ScalarSamplesResponse>;
  latestHrv: ApiResult<ScalarSamplesResponse>;
  bloodPressure: ApiResult<BloodPressureMeasurementsResponse>;
  latestBloodPressure: ApiResult<BloodPressureMeasurementsResponse>;
  cardiovascular: ApiResult<ScalarSamplesResponse>;
  extendedBodyMeasurements: ApiResult<ScalarSamplesResponse>;
};

/**
 * The page data with each field left as an unresolved promise, so the route can
 * fire every request up front and stream sections in as their own data settles
 * rather than blocking first paint on the slowest fetch.
 */
export type HealthDataPageSources = {
  [K in keyof HealthDataPageData]: K extends "apiBaseUrl"
    ? HealthDataPageData[K]
    : Promise<HealthDataPageData[K]>;
};

export type TrendsPageData = HealthStatusData & {
  fromDate: string;
  toDate: string;
  weight: ApiResult<ScalarSamplesResponse>;
  steps: ApiResult<StepDailySummariesResponse>;
  sleep: ApiResult<SleepSummariesResponse>;
  hrv: ApiResult<ScalarSamplesResponse>;
  activity: ApiResult<ActivitySummariesResponse>;
  respiratory: ApiResult<ScalarSamplesResponse>;
};

export type ProviderSyncPageData = HealthStatusData & {
  providerCatalog: ApiResult<ProviderCatalogResponse>;
  providerStatuses: ApiResult<ProviderStatusCatalogResponse>;
  scheduledSyncConfigs: ApiResult<ScheduledSyncConfig>[];
};

export type IngestionsPageData = HealthStatusData & {
  batches: ApiResult<IngestionBatchesResponse>;
  failures: ApiResult<IngestionBatchesResponse>;
};
