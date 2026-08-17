import type {
  ApiResult,
  HealthDataPageSources,
  HealthDayModuleName,
  HealthDayResponse,
  IngestionBatchDetailResponse,
  IngestionsPageData,
  ProviderSyncPageData,
  TrendsPageData,
  HealthStatusData,
  HeartRateDailyPoint,
} from "./types";
import { aqtHealthClient, toProviderCode } from "./aqtHealthClient";
import { toPositiveInteger } from "./format";
import {
  addUtcDays,
  dateOnlyToUtcInstant,
  dayAfterDateOnlyToUtcInstant,
  first,
} from "./dates";

export async function getHealthStatus(): Promise<HealthStatusData> {
  return {
    apiBaseUrl: aqtHealthClient.apiBaseUrl,
    health: await aqtHealthClient.getHealth(),
  };
}

/**
 * Fires every health-data request without awaiting so the route can wrap each
 * section in its own Suspense boundary and stream results independently. The
 * requests still run concurrently, exactly as the previous single `Promise.all`
 * did, but no section blocks first paint on the slowest fetch (the per-day
 * heart-rate fan-out in particular).
 */
export function getHealthDataPageSources(
  fromDate: string,
  toDate: string,
  timezone: string,
): HealthDataPageSources {
  const client = aqtHealthClient;
  const measurementsFrom = dateOnlyToUtcInstant(fromDate);
  const measurementsTo = dayAfterDateOnlyToUtcInstant(toDate);

  return {
    apiBaseUrl: client.apiBaseUrl,
    health: client.getHealth(),
    summary: client.getDashboardSummary({ fromDate, toDate }),
    trends: client.getDashboardTrends({ periodDays: 7, toDate }),
    healthDay: getHealthDay({
      date: toDate,
      timezone,
      modules: ["steps", "heartRate", "weight", "sleep"],
      includeSource: true,
    }),
    dailySteps: client.listDailyStepSummaries({ fromDate, toDate, includeSource: true }),
    activitySummaries: client.listActivitySummaries({
      fromDate,
      toDate,
      includeSource: true,
      sort: "date",
      order: "desc",
      limit: 5000,
    }),
    bodyMeasurements: client.listBodyMeasurements({
      from: measurementsFrom,
      to: measurementsTo,
      includeSource: true,
      sort: "measuredAt",
      order: "desc",
      limit: 5000,
    }),
    latestHeartRate: client.listHeartRateSamples({ latest: true, includeSource: true }),
    heartRateDaily: fetchHeartRateDaily(fromDate, toDate),
    sleepNights: client.listSleepNights({ fromDate, toDate, timezone, includeSource: true }),
    sleepSummaries: client.listSleepSummaries({
      from: measurementsFrom,
      to: measurementsTo,
      includeSource: true,
      sort: "endAt",
      order: "desc",
      limit: 5000,
    }),
    respiratoryRates: client.listRespiratoryRateSamples({
      from: measurementsFrom,
      to: measurementsTo,
      includeSource: true,
      sort: "measuredAt",
      order: "desc",
      limit: 5000,
    }),
    hrvSamples: client.listHrvSamples({
      from: measurementsFrom,
      to: measurementsTo,
      includeSource: true,
      sort: "measuredAt",
      order: "desc",
      limit: 5000,
    }),
    latestActivity: client.getLatestActivitySummary({ date: toDate, includeSource: true }),
    latestSleepSummary: client.getLatestSleepSummary({ includeSource: true }),
    latestRespiratoryRate: client.listRespiratoryRateSamples({ latest: true, includeSource: true }),
    latestHrv: client.listHrvSamples({ latest: true, includeSource: true }),
    bloodPressure: client.listBloodPressure({
      from: measurementsFrom,
      to: measurementsTo,
      includeSource: true,
      sort: "measuredAt",
      order: "desc",
      limit: 5000,
    }),
    latestBloodPressure: client.getLatestBloodPressure({ includeSource: true }),
    cardiovascular: client.listCardiovascular({
      from: measurementsFrom,
      to: measurementsTo,
      includeSource: true,
      sort: "measuredAt",
      order: "desc",
      limit: 5000,
    }),
    extendedBodyMeasurements: client.listExtendedBodyMeasurements({
      from: measurementsFrom,
      to: measurementsTo,
      includeSource: true,
      sort: "measuredAt",
      order: "desc",
      limit: 5000,
    }),
  };
}

export async function getTrendsPageData(
  toDate: string,
  days: number,
): Promise<TrendsPageData> {
  const client = aqtHealthClient;
  const fromDate = addUtcDays(toDate, -(days - 1));
  const from = dateOnlyToUtcInstant(fromDate);
  const to = dayAfterDateOnlyToUtcInstant(toDate);
  const sampleQuery = {
    from,
    to,
    includeSource: true,
    sort: "measuredAt" as const,
    order: "asc" as const,
    limit: 5000,
  };

  const [health, weight, steps, sleep, hrv, activity, respiratory] = await Promise.all([
    client.getHealth(),
    client.listBodyMeasurements(sampleQuery),
    client.listDailyStepSummaries({ fromDate, toDate, includeSource: true }),
    client.listSleepSummaries({
      from,
      to,
      includeSource: true,
      sort: "endAt",
      order: "asc",
      limit: 5000,
    }),
    client.listHrvSamples(sampleQuery),
    client.listActivitySummaries({
      fromDate,
      toDate,
      includeSource: true,
      sort: "date",
      order: "asc",
      limit: 5000,
    }),
    client.listRespiratoryRateSamples(sampleQuery),
  ]);

  return {
    apiBaseUrl: client.apiBaseUrl,
    health,
    fromDate,
    toDate,
    weight,
    steps,
    sleep,
    hrv,
    activity,
    respiratory,
  };
}

export async function getProviderSyncPageData(): Promise<ProviderSyncPageData> {
  const client = aqtHealthClient;
  const [health, providerCatalog, providerStatuses] = await Promise.all([
    client.getHealth(),
    client.listProviders(),
    client.listProviderStatuses(),
  ]);
  const scheduledSyncConfigs =
    providerStatuses.ok
      ? await Promise.all(
          providerStatuses.data.items.flatMap((provider) => {
            const providerCode = toProviderCode(provider.providerCode);
            if (!providerCode) return [];
            return provider.accounts.map((account) =>
              client.getScheduledSyncConfig(
                providerCode,
                account.providerInstanceId,
              ),
            );
          }),
        )
      : [];

  return {
    apiBaseUrl: client.apiBaseUrl,
    health,
    providerCatalog,
    providerStatuses,
    scheduledSyncConfigs,
  };
}

export async function getIngestionsPageData(options: {
  limit?: string | string[];
  status?: string | string[];
}): Promise<IngestionsPageData> {
  const client = aqtHealthClient;
  const limit = toPositiveInteger(first(options.limit) ?? "") ?? 25;
  const status = ingestionStatus(first(options.status));

  const [health, batches, failures] = await Promise.all([
    client.getHealth(),
    client.listIngestionBatches({ limit, status }),
    client.listIngestionFailures({ limit }),
  ]);

  return {
    apiBaseUrl: client.apiBaseUrl,
    health,
    batches,
    failures,
  };
}

export async function getIngestionBatchDetail(
  id: string,
): Promise<ApiResult<IngestionBatchDetailResponse>> {
  const parsed = Number(id);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    return { ok: false, message: "Ingestion batch id must be a positive integer." };
  }

  return aqtHealthClient.getIngestionBatch(parsed);
}

async function getHealthDay(paramsValue: {
  date: string;
  timezone: string;
  modules: HealthDayModuleName[];
  includeSource?: boolean;
}): Promise<ApiResult<HealthDayResponse>> {
  return aqtHealthClient.getHealthDay({
    date: paramsValue.date,
    timezone: paramsValue.timezone,
    modules: paramsValue.modules.join(","),
    includeSource: paramsValue.includeSource ?? false,
  });
}

/** Bounds the daily heart-rate query window to the most recent stretch of days. */
const MAX_HEART_RATE_DAILY_DAYS = 92;

/**
 * Builds a per-day heart-rate series (avg/min/max) across the range in one request. The scalar
 * `/daily` endpoint buckets by UTC calendar day server-side, so we send the full range instead of
 * charting hundreds of thousands of raw samples or fanning out one request per day.
 */
async function fetchHeartRateDaily(
  fromDate: string,
  toDate: string,
): Promise<HeartRateDailyPoint[]> {
  const earliest = addUtcDays(toDate, -(MAX_HEART_RATE_DAILY_DAYS - 1));
  const from = fromDate < earliest ? earliest : fromDate;

  const result = await aqtHealthClient.getScalarDailySummaries("heart_rate", {
    from: dateOnlyToUtcInstant(from),
    to: dayAfterDateOnlyToUtcInstant(toDate),
  });
  if (!result.ok) return [];

  return result.data.items
    .filter((item) => item.count > 0)
    .map((item) => ({
      date: item.date,
      count: item.count,
      avg: item.avgValue ?? null,
      min: item.minValue ?? null,
      max: item.maxValue ?? null,
    }));
}

function ingestionStatus(value?: string): "processed" | "failed" | undefined {
  if (value === "processed" || value === "failed") return value;
  return undefined;
}
