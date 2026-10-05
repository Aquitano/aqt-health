import type {
  ApiResult,
  ApiSchema,
  HealthDayModuleName,
  IngestionBatch,
  IngestionBatchDetailResponse,
} from "./types";
import { aqtHealthClient as client, toProviderCode } from "./aqtHealthClient";
import { toPositiveInteger } from "./format";
import { addUtcDays, rangeDays, startOfDayInstant } from "./dates";

/** Start independent section requests before rendering their Suspense boundaries. */
export function getHealthDataPageSources(fromDate: string, toDate: string, timezone: string) {
  const measurementsFrom = startOfDayInstant(fromDate, timezone);
  const measurementsTo = startOfDayInstant(addUtcDays(toDate, 1), timezone);
  const samplesQuery = {
    from: measurementsFrom,
    to: measurementsTo,
    includeSource: true,
    order: "desc" as const,
    limit: 5000,
  };
  const healthDayModules: HealthDayModuleName[] = ["steps", "heartRate", "weight", "sleep"];

  return {
    health: client.getHealth(),
    summary: client.getDashboardSummary({ fromDate, toDate, timezone }),
    trends: client.getDashboardTrends({ periodDays: Math.min(rangeDays(fromDate, toDate), 90), toDate, timezone }),
    healthDay: client.getHealthDay({
      date: toDate,
      timezone,
      modules: healthDayModules.join(","),
      includeSource: true,
    }),
    dailySteps: client.listDailyStepSummaries({ fromDate, toDate, timezone, includeSource: true }),
    activitySummaries: client.listActivitySummaries({
      fromDate,
      toDate,
      includeSource: true,
      order: "desc",
      limit: 5000,
    }),
    bodyMeasurements: client.listBodyMeasurements(samplesQuery),
    heartRateDaily: client.getScalarDailySummaries("heart_rate", { from: measurementsFrom, to: measurementsTo, timezone }),
    sleepNights: client.listSleepNights({ fromDate, toDate, timezone, includeSource: true }),
    sleepSummaries: client.listSleepSummaries(samplesQuery),
    respiratoryRates: client.listScalarSamples("respiratory_rate", samplesQuery),
    hrvSamples: client.listScalarSamples("hrv_rmssd", samplesQuery),
    latestActivity: client.listActivitySummaries({ date: toDate, includeSource: true, latest: true }),
    latestSleepSummary: client.listSleepSummaries({ includeSource: true, latest: true }),
    latestRespiratoryRate: client.listScalarSamples("respiratory_rate", { includeSource: true, latest: true }),
    latestHrv: client.listScalarSamples("hrv_rmssd", { includeSource: true, latest: true }),
    latestBloodPressure: client.listBloodPressure({ includeSource: true, latest: true }),
  };
}

export type HealthDataPageSources = ReturnType<typeof getHealthDataPageSources>;

export async function getTrendsPageData(toDate: string, days: number, timezone: string) {
  const fromDate = addUtcDays(toDate, -(days - 1));
  const from = startOfDayInstant(fromDate, timezone);
  const to = startOfDayInstant(addUtcDays(toDate, 1), timezone);
  const sampleQuery = {
    from,
    to,
    includeSource: true,
    order: "asc" as const,
    limit: 5000,
  };

  const [health, weight, steps, sleep, hrv, activity, respiratory] = await Promise.all([
    client.getHealth(),
    readAllPages((cursor) => client.listScalarSamples("weight", { ...sampleQuery, cursor })),
    readAllPages((cursor) => client.listDailyStepSummaries({ fromDate, toDate, timezone, limit: 5000, cursor })),
    readAllPages((cursor) => client.listSleepSummaries({ ...sampleQuery, cursor })),
    client.getScalarDailySummaries("hrv_rmssd", { from, to, timezone }),
    readAllPages((cursor) => client.listActivitySummaries({
      cursor,
      fromDate,
      toDate,
      includeSource: true,
      order: "asc",
      limit: 5000,
    })),
    client.getScalarDailySummaries("respiratory_rate", { from, to, timezone }),
  ]);

  return { health, weight, steps, sleep, hrv, activity, respiratory };
}

export async function getProviderSyncPageData() {
  const [health, providerCatalog, providerStatuses] = await Promise.all([
    client.getHealth(),
    client.listProviders(),
    client.listProviderStatuses(),
  ]);
  const providers = providerStatuses.ok
    ? providerStatuses.data.items.flatMap((provider) => {
        const providerCode = toProviderCode(provider.providerCode);
        return providerCode ? [{ providerCode, accounts: provider.accounts }] : [];
      })
    : [];
  const [scheduledSyncConfigs, latestSyncJobs] = await Promise.all([
    Promise.all(
      providers.flatMap(({ providerCode, accounts }) =>
        accounts.map((account) =>
          client.getScheduledSyncConfig(
            providerCode,
            account.providerInstanceId,
          ),
        ),
      ),
    ),
    Promise.all(
      providers.map(({ providerCode }) =>
        client.getLatestProviderSyncJob(providerCode),
      ),
    ),
  ]);
  const runningSyncJob =
    latestSyncJobs
      .flatMap((job) => (job.ok && !job.data.terminal ? [job.data] : []))
      .at(0) ?? null;

  return { health, providerCatalog, providerStatuses, scheduledSyncConfigs, runningSyncJob };
}

const ingestionStatuses: IngestionBatch["status"][] = ["received", "processed", "failed"];

export async function getIngestionsPageData(options: { limit?: string; status?: string }) {
  const limit = toPositiveInteger(options.limit) ?? 25;
  const status = ingestionStatuses.find((candidate) => candidate === options.status);

  const [health, batches, failures] = await Promise.all([
    client.getHealth(),
    client.listIngestionBatches({ limit, status }),
    client.listIngestionFailures({ limit }),
  ]);

  return { health, batches, failures };
}

export async function getIngestionBatchDetail(
  id: string,
): Promise<ApiResult<IngestionBatchDetailResponse>> {
  const parsed = toPositiveInteger(id);
  if (parsed === undefined) {
    return { ok: false, message: "Ingestion batch id must be a positive integer." };
  }

  return client.getIngestionBatch(parsed);
}

type ReadPage<T> = { items: T[]; meta: ApiSchema<"ReadResponseMeta"> };

const maxReadPages = 20;

async function readAllPages<T>(
  readPage: (cursor?: string) => Promise<ApiResult<ReadPage<T>>>,
): Promise<ApiResult<ReadPage<T>>> {
  const items: T[] = [];
  let cursor: string | undefined;
  for (let pages = 0; pages < maxReadPages; pages++) {
    const page = await readPage(cursor);
    if (!page.ok) return page;
    const nextCursor = page.data.meta.nextCursor ?? undefined;
    if (nextCursor && nextCursor === cursor) {
      return { ok: false, message: "The backend repeated a pagination cursor." };
    }
    items.push(...page.data.items);
    cursor = nextCursor;
    if (!cursor) return { ok: true, data: { items, meta: { ...page.data.meta, count: items.length } } };
  }
  return { ok: false, message: `Stopped after ${maxReadPages} pages; the backend kept returning a next cursor.` };
}
