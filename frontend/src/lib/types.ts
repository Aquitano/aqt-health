import type { components } from "./generated/aqtHealthApiTypes";

export type ApiSchema<Name extends keyof components["schemas"]> =
  components["schemas"][Name];

export type ApiResult<T> =
  | { ok: true; data: T }
  | { ok: false; status?: number; message: string };

export type HealthResponse = ApiSchema<"HealthResponse">;
export type SourceMetadata = ApiSchema<"SourceMetadataResponse">;
export type ReadResponseMeta = ApiSchema<"ReadResponseMeta">;
export type StepDailySummary = ApiSchema<"StepDailySummaryResponse">;
export type StepDailySummariesResponse = ApiSchema<"StepDailySummariesResponse">;
export type BodyMeasurement = ApiSchema<"BodyMeasurementResponse">;
export type BodyMeasurementsResponse = ApiSchema<"BodyMeasurementsResponse">;
export type BodyMeasurementLatestResponse = ApiSchema<"BodyMeasurementLatestResponse">;
export type ActivitySummary = {
  id: number;
  date: string;
  distanceMeters?: number | null;
  activeEnergyKcal?: number | null;
  totalEnergyKcal?: number | null;
  elevationMeters?: number | null;
  softMinutes?: number | null;
  moderateMinutes?: number | null;
  intenseMinutes?: number | null;
  activeMinutes?: number | null;
  averageHeartRateBpm?: number | null;
  minHeartRateBpm?: number | null;
  maxHeartRateBpm?: number | null;
  source?: SourceMetadata | null;
};
export type ActivitySummariesResponse = {
  items: ActivitySummary[];
  meta: ReadResponseMeta;
};
export type ActivitySummaryLatestResponse = {
  item?: ActivitySummary | null;
};
export type HeartRateSample = ApiSchema<"HeartRateSampleResponse">;
export type HeartRateSamplesResponse = ApiSchema<"HeartRateSamplesResponse">;
export type HeartRateSummaryResponse = ApiSchema<"HeartRateSummaryResponse">;
export type RespiratoryRateSample = {
  id: number;
  measuredAt: string;
  breathsPerMinute: number;
  context: string;
  source?: SourceMetadata | null;
};
export type RespiratoryRateSamplesResponse = {
  items: RespiratoryRateSample[];
  meta: ReadResponseMeta;
};
export type RespiratoryRateSummaryResponse = {
  count: number;
  minBreathsPerMinute?: number | null;
  maxBreathsPerMinute?: number | null;
  avgBreathsPerMinute?: number | null;
  latest?: RespiratoryRateSample | null;
};
export type HrvSample = {
  id: number;
  measuredAt: string;
  metricType: string;
  value: number;
  unit: string;
  context: string;
  source?: SourceMetadata | null;
};
export type HrvSamplesResponse = {
  items: HrvSample[];
  meta: ReadResponseMeta;
};
export type HrvSummaryResponse = {
  count: number;
  metricType: string;
  minValue?: number | null;
  maxValue?: number | null;
  avgValue?: number | null;
  latest?: HrvSample | null;
};
export type SleepStage = ApiSchema<"SleepStageResponse">;
export type SleepSession = ApiSchema<"SleepSessionResponse">;
export type SleepSessionsResponse = ApiSchema<"SleepSessionsResponse">;
export type SleepSummary = {
  id: number;
  startAt: string;
  endAt: string;
  timeInBedSeconds?: number | null;
  totalSleepSeconds?: number | null;
  lightSleepSeconds?: number | null;
  deepSleepSeconds?: number | null;
  remSleepSeconds?: number | null;
  sleepEfficiencyPercent?: number | null;
  sleepLatencySeconds?: number | null;
  wakeupLatencySeconds?: number | null;
  wakeupDurationSeconds?: number | null;
  wakeupCount?: number | null;
  wasoSeconds?: number | null;
  sleepScore?: number | null;
  source?: SourceMetadata | null;
};
export type SleepSummariesResponse = {
  items: SleepSummary[];
  meta: ReadResponseMeta;
};
export type SleepSummaryLatestResponse = {
  item?: SleepSummary | null;
};
export type SleepNight = ApiSchema<"SleepNightResponse">;
export type SleepNightsResponse = ApiSchema<"SleepNightsResponse">;
export type DashboardSummaryResponse = ApiSchema<"DashboardSummaryResponse">;
export type HealthDayBucket = ApiSchema<"HealthDayBucketResponse">;
export type HealthDayResponse = ApiSchema<"HealthDayResponse">;
export type IngestionBatch = ApiSchema<"IngestionBatchAdminResponse">;
export type IngestionBatchesResponse = ApiSchema<"IngestionBatchesResponse">;
export type IngestionRecordAdmin = ApiSchema<"IngestionRecordAdminResponse">;
export type IngestionBatchDetailResponse = ApiSchema<"IngestionBatchDetailResponse">;
export type ProviderCatalogResponse = ApiSchema<"ProviderCatalogResponseDto">;
export type ProviderDescriptor = ApiSchema<"ProviderDescriptorResponseDto">;
export type ProviderWorkflowEndpoints = ApiSchema<"ProviderWorkflowEndpointsResponseDto">;
export type ProviderOAuthStartResponse = ApiSchema<"ProviderOAuthStartResponse">;
export type ProviderSyncRequest = ApiSchema<"ProviderSyncRequestDto">;
export type ProviderSyncResponse = ApiSchema<"ProviderSyncResponseDto">;
export type ProviderSyncBatch = ApiSchema<"ProviderSyncBatchResponseDto">;
export type ProviderSyncError = ApiSchema<"ProviderSyncErrorResponseDto">;
export type ProviderSyncEmptyDataType = ApiSchema<"ProviderSyncEmptyDataTypeResponseDto">;
export type ProviderStatusCatalogResponse = ApiSchema<"ProviderStatusCatalogResponseDto">;
export type ProviderStatus = ApiSchema<"ProviderStatusResponseDto">;
export type ProviderAccountStatus = ApiSchema<"ProviderAccountStatusResponseDto">;
export type MetricCatalogResponse = ApiSchema<"MetricCatalogResponseDto">;
export type MetricFamilyCatalog = ApiSchema<"MetricFamilyCatalogDto">;
export type MetricReadEndpoint = ApiSchema<"MetricReadEndpointDto">;
export type MetricQueryParameter = ApiSchema<"MetricQueryParameterDto">;
export type MetricAggregationMode = ApiSchema<"MetricAggregationModeDto">;
export type MetricProviderDataTypes = ApiSchema<"MetricProviderDataTypesDto">;

export type HealthDayModuleName = HealthDayResponse["modules"][number];

export type HealthStatusData = {
  apiBaseUrl: string;
  health: ApiResult<HealthResponse>;
};

export type HealthDataPageData = HealthStatusData & {
  summary: ApiResult<DashboardSummaryResponse>;
  healthDay: ApiResult<HealthDayResponse>;
  dailySteps: ApiResult<StepDailySummariesResponse>;
  activitySummaries: ApiResult<ActivitySummariesResponse>;
  bodyMeasurements: ApiResult<BodyMeasurementsResponse>;
  latestHeartRate: ApiResult<HeartRateSamplesResponse>;
  latestSleep: ApiResult<SleepNightsResponse>;
  sleepSummaries: ApiResult<SleepSummariesResponse>;
  respiratoryRates: ApiResult<RespiratoryRateSamplesResponse>;
  hrvSamples: ApiResult<HrvSamplesResponse>;
  latestActivity: ApiResult<ActivitySummaryLatestResponse>;
  latestSleepSummary: ApiResult<SleepSummaryLatestResponse>;
  latestRespiratoryRate: ApiResult<RespiratoryRateSamplesResponse>;
  latestHrv: ApiResult<HrvSamplesResponse>;
  metricCatalog: ApiResult<MetricCatalogResponse>;
};

export type ProviderSyncPageData = HealthStatusData & {
  providerCatalog: ApiResult<ProviderCatalogResponse>;
  providerStatuses: ApiResult<ProviderStatusCatalogResponse>;
};

export type IngestionsPageData = HealthStatusData & {
  batches: ApiResult<IngestionBatchesResponse>;
  failures: ApiResult<IngestionBatchesResponse>;
};
