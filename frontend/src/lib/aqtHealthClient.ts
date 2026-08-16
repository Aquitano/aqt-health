import createClient from "openapi-fetch";
import type {
  ApiResult,
  ApiSchema,
  ScheduledSyncConfig,
  ScheduledSyncConfigUpdateRequest,
  ScheduledSyncRunResponse,
} from "./types";
import type { paths } from "./generated/aqtHealthApiTypes";

type ClientResponse<T> = {
  data?: T;
  error?: unknown;
  response?: Response;
};

type ClientOptions = {
  protected?: boolean;
};
type AqtOpenApiClient = ReturnType<typeof createClient<paths>>;

/** Query parameters of a GET endpoint in the generated OpenAPI paths. */
type GetQuery<Path extends keyof paths> = paths[Path] extends {
  get: { parameters: { query?: infer Query } };
}
  ? NonNullable<Query>
  : never;

export type ProviderCode =
  paths["/api/v2/providers/{providerCode}/sync-jobs"]["post"]["parameters"]["path"]["providerCode"];

// Record<ProviderCode, true> fails to compile if the generated union and this map ever diverge.
const PROVIDER_CODE_SET: Record<ProviderCode, true> = { "google-health": true, withings: true };

/** Narrows a runtime string (path segment, API response field) to a known provider code. */
export function toProviderCode(value: string): ProviderCode | null {
  return Object.hasOwn(PROVIDER_CODE_SET, value) ? (value as ProviderCode) : null;
}

const bodyMetricTypes = ["weight", "body_fat", "muscle", "water", "visceral_fat"];
const cardiovascularMetricTypes = ["pulse_wave_velocity", "vascular_age", "standing_heart_rate"];
const extendedBodyMetricTypes = [
  "fat_mass",
  "fat_free_mass",
  "bone_mass",
  "intracellular_water",
  "extracellular_water",
  "basal_metabolic_rate",
  "segmental_fat_mass",
  "segmental_muscle_mass",
  "segmental_fat_free_mass",
];

const defaultBaseUrl = "http://localhost:8080";
const backendRequestTimeoutMs = 8_000;
const longRunningBackendRequestTimeoutMs = 300_000;

export function apiBaseUrlFromEnv(): string {
  return process.env.AQT_HEALTH_API_BASE_URL ?? defaultBaseUrl;
}

const apiBaseUrl = apiBaseUrlFromEnv();
const rawClient = createClient<paths>({
  baseUrl: apiBaseUrl,
  fetch: (input: Request) => fetchWithTimeout(input),
});
const longRunningClient = createClient<paths>({
  baseUrl: apiBaseUrl,
  fetch: (input: Request) => fetchWithTimeout(input, undefined, longRunningBackendRequestTimeoutMs),
});

export const aqtHealthClient = {
  apiBaseUrl,

  getHealth: () =>
    call<ApiSchema<"HealthResponse">>(() => rawClient.GET("/api/v2/admin/health"), {
      protected: false,
    }),

  listIngestionBatches: (query: GetQuery<"/api/v2/admin/ingestion/batches">) =>
    call<ApiSchema<"IngestionBatchesResponse">>(
      (headers) =>
        rawClient.GET("/api/v2/admin/ingestion/batches", {
          headers,
          params: { query },
        }),
    ),

  getIngestionBatch: (id: number) =>
    call<ApiSchema<"IngestionBatchDetailResponse">>(
      (headers) =>
        rawClient.GET("/api/v2/admin/ingestion/batches/{id}", {
          headers,
          params: { path: { id } },
        }),
    ),

  listIngestionFailures: (query: GetQuery<"/api/v2/admin/ingestion/failures">) =>
    call<ApiSchema<"IngestionBatchesResponse">>(
      (headers) =>
        rawClient.GET("/api/v2/admin/ingestion/failures", {
          headers,
          params: { query },
        }),
    ),

  listProviders: () =>
    call<ApiSchema<"ProviderCatalogResponse">>((headers) =>
      rawClient.GET("/api/v2/providers", { headers }),
    ),

  listProviderStatuses: () =>
    call<ApiSchema<"ProviderStatusCatalogResponse">>((headers) =>
      rawClient.GET("/api/v2/providers/status", { headers }),
    ),

  startProviderOAuth: (providerCode: ProviderCode) =>
    call<ApiSchema<"ProviderOAuthStartResponse">>((headers) =>
      rawClient.GET("/api/v2/providers/{providerCode}/oauth/start", {
        headers,
        params: { path: { providerCode } },
      }),
    ),

  disconnectProviderAccount: (providerCode: ProviderCode, providerInstanceId: string) =>
    call<ApiSchema<"ProviderDisconnectResponse">>((headers) =>
      rawClient.POST("/api/v2/providers/{providerCode}/accounts/{providerInstanceId}/disconnect", {
        headers,
        params: { path: { providerCode, providerInstanceId } },
      }),
    ),

  reconnectProviderAccount: (providerCode: ProviderCode, providerInstanceId: string) =>
    call<ApiSchema<"ProviderOAuthStartResponse">>((headers) =>
      rawClient.POST("/api/v2/providers/{providerCode}/accounts/{providerInstanceId}/reconnect", {
        headers,
        params: { path: { providerCode, providerInstanceId } },
      }),
    ),

  getScheduledSyncConfig: (providerCode: ProviderCode, providerInstanceId: string) =>
    call<ScheduledSyncConfig>((headers) =>
      rawClient.GET("/api/v2/providers/{providerCode}/accounts/{providerInstanceId}/scheduled-sync", {
        headers,
        params: { path: { providerCode, providerInstanceId } },
      }),
    ),

  updateScheduledSyncConfig: (
    providerCode: ProviderCode,
    providerInstanceId: string,
    body: ScheduledSyncConfigUpdateRequest,
  ) =>
    call<ScheduledSyncConfig>((headers) =>
      rawClient.PUT("/api/v2/providers/{providerCode}/accounts/{providerInstanceId}/scheduled-sync", {
        body,
        headers,
        params: { path: { providerCode, providerInstanceId } },
      }),
    ),

  runScheduledSyncNow: (providerCode: ProviderCode, providerInstanceId: string) =>
    call<ScheduledSyncRunResponse>((headers) =>
      longRunningClient.POST(
        "/api/v2/providers/{providerCode}/accounts/{providerInstanceId}/scheduled-sync/run",
        {
          headers,
          params: { path: { providerCode, providerInstanceId } },
        },
      ),
    ),

  startProviderSyncJob: (providerCode: ProviderCode, body: ApiSchema<"ProviderSyncRequest">) =>
    call<ApiSchema<"ProviderSyncJobStartResponse">>((headers) =>
      rawClient.POST("/api/v2/providers/{providerCode}/sync-jobs", {
        body,
        headers,
        params: { path: { providerCode } },
      }),
    ),

  getProviderSyncJob: (providerCode: ProviderCode, jobId: string) =>
    call<ApiSchema<"ProviderSyncJobStatusResponse">>((headers) =>
      rawClient.GET("/api/v2/providers/{providerCode}/sync-jobs/{jobId}", {
        headers,
        params: { path: { providerCode, jobId } },
      }),
    ),

  getMetricCatalog: () =>
    call<ApiSchema<"MetricTypeCatalogResponse">>((headers) =>
      rawClient.GET("/api/v2/metrics", { headers }),
    ),

  getHealthDay: (query: GetQuery<"/api/v2/health/day">) =>
    call<ApiSchema<"HealthDayResponse">>((headers) =>
      rawClient.GET("/api/v2/health/day", {
        headers,
        params: { query },
      }),
    ),

  listDailyStepSummaries: (query: GetQuery<"/api/v2/steps/daily">) =>
    call<ApiSchema<"StepDailySummariesResponse">>((headers) =>
      rawClient.GET("/api/v2/steps/daily", {
        headers,
        params: { query },
      }),
    ),

  listActivitySummaries: (query: GetQuery<"/api/v2/activity/summaries">) =>
    call<ApiSchema<"ActivitySummariesResponse">>((headers) =>
      rawClient.GET("/api/v2/activity/summaries", {
        headers,
        params: { query },
      }),
    ),

  getLatestActivitySummary: (query: GetQuery<"/api/v2/activity/summaries">) =>
    call<ApiSchema<"ActivitySummariesResponse">>((headers) =>
      rawClient.GET("/api/v2/activity/summaries", {
        headers,
        params: { query: { ...query, latest: true } },
      }),
    ),

  listHeartRateSamples: (query: ScalarSamplesQuery) =>
    listScalarMetric(rawClient, "heart_rate", query),

  getScalarSummary: (metricType: string, query: GetQuery<"/api/v2/metrics/{metricType}/summary">) =>
    call<ApiSchema<"ScalarSummaryResponse">>((headers) =>
      rawClient.GET("/api/v2/metrics/{metricType}/summary", {
        headers,
        params: { path: { metricType }, query },
      }),
    ),

  getScalarDailySummaries: (
    metricType: string,
    query: GetQuery<"/api/v2/metrics/{metricType}/daily">,
  ) =>
    call<ApiSchema<"ScalarDailySummariesResponse">>((headers) =>
      rawClient.GET("/api/v2/metrics/{metricType}/daily", {
        headers,
        params: { path: { metricType }, query },
      }),
    ),

  listRespiratoryRateSamples: (query: ScalarSamplesQuery) =>
    listScalarMetric(rawClient, "respiratory_rate", query),

  listHrvSamples: (query: ScalarSamplesQuery) =>
    listScalarMetric(rawClient, "hrv_rmssd", query),

  listSleepNights: (query: GetQuery<"/api/v2/sleep/nights">) =>
    call<ApiSchema<"SleepNightsResponse">>((headers) =>
      rawClient.GET("/api/v2/sleep/nights", {
        headers,
        params: { query },
      }),
    ),

  listSleepSummaries: (query: GetQuery<"/api/v2/sleep/summaries">) =>
    call<ApiSchema<"SleepSummariesResponse">>((headers) =>
      rawClient.GET("/api/v2/sleep/summaries", {
        headers,
        params: { query },
      }),
    ),

  getLatestSleepSummary: (query: GetQuery<"/api/v2/sleep/summaries">) =>
    call<ApiSchema<"SleepSummariesResponse">>((headers) =>
      rawClient.GET("/api/v2/sleep/summaries", {
        headers,
        params: { query: { ...query, latest: true } },
      }),
    ),

  getLatestBodyMeasurement: (query: ScalarSamplesQuery) =>
    listScalarMetric(rawClient, "weight", { ...query, latest: true }),

  listBodyMeasurements: (query: ScalarSamplesQuery) =>
    listScalarMetrics(rawClient, bodyMetricTypes, query),

  getDashboardSummary: (query: GetQuery<"/api/v2/dashboard/summary">) =>
    call<ApiSchema<"DashboardSummaryResponse">>((headers) =>
      rawClient.GET("/api/v2/dashboard/summary", {
        headers,
        params: { query },
      }),
    ),

  getDashboardTrends: (query: GetQuery<"/api/v2/dashboard/trends">) =>
    call<ApiSchema<"DashboardTrendsResponse">>((headers) =>
      rawClient.GET("/api/v2/dashboard/trends", {
        headers,
        params: { query },
      }),
    ),

  listBloodPressure: (query: GetQuery<"/api/v2/blood-pressure">) =>
    call<ApiSchema<"BloodPressureMeasurementsResponse">>((headers) =>
      rawClient.GET("/api/v2/blood-pressure", {
        headers,
        params: { query },
      }),
    ),

  getLatestBloodPressure: (query: GetQuery<"/api/v2/blood-pressure">) =>
    call<ApiSchema<"BloodPressureMeasurementsResponse">>((headers) =>
      rawClient.GET("/api/v2/blood-pressure", {
        headers,
        params: { query: { ...query, latest: true } },
      }),
    ),

  listCardiovascular: (query: ScalarSamplesQuery) =>
    listScalarMetrics(rawClient, cardiovascularMetricTypes, query),

  getLatestCardiovascular: (query: ScalarSamplesQuery) =>
    listScalarMetrics(rawClient, cardiovascularMetricTypes, { ...query, latest: true }),

  listExtendedBodyMeasurements: (query: ScalarSamplesQuery) =>
    listScalarMetrics(rawClient, extendedBodyMetricTypes, query),

  getLatestExtendedBodyMeasurement: (query: ScalarSamplesQuery) =>
    listScalarMetrics(rawClient, extendedBodyMetricTypes, { ...query, latest: true }),
};

export type AqtHealthClient = typeof aqtHealthClient;

type ScalarSamplesQuery = GetQuery<"/api/v2/metrics/{metricType}">;

function listScalarMetric(
  client: AqtOpenApiClient,
  metricType: string,
  query: ScalarSamplesQuery,
): Promise<ApiResult<ApiSchema<"ScalarSamplesResponse">>> {
  return call<ApiSchema<"ScalarSamplesResponse">>((headers) =>
    client.GET("/api/v2/metrics/{metricType}", {
      headers,
      params: { path: { metricType }, query },
    }),
  );
}

function listScalarMetrics(
  client: AqtOpenApiClient,
  metricTypes: string[],
  query: ScalarSamplesQuery,
): Promise<ApiResult<ApiSchema<"ScalarSamplesResponse">>> {
  return call<ApiSchema<"ScalarSamplesResponse">>((headers) =>
    mergedScalarMetrics(client, metricTypes, query, headers),
  );
}

async function mergedScalarMetrics(
  client: AqtOpenApiClient,
  metricTypes: string[],
  query: ScalarSamplesQuery,
  headers: HeadersInit,
): Promise<ClientResponse<ApiSchema<"ScalarSamplesResponse">>> {
  const responses = await Promise.all(
    metricTypes.map((metricType) =>
      client.GET("/api/v2/metrics/{metricType}", {
        headers,
        params: { path: { metricType }, query },
      }),
    ),
  );
  const failed = responses.find((result) => result.error || !result.response?.ok);
  if (failed) return failed as ClientResponse<ApiSchema<"ScalarSamplesResponse">>;

  const order = query.order ?? (query.latest ? "desc" : "asc");
  const requestedLimit = query.limit ?? 500;
  const mergedItems = responses
    .flatMap((result) => result.data?.items ?? [])
    .sort((left, right) => {
      const measured = left.measuredAt.localeCompare(right.measuredAt);
      const byTime = order === "desc" ? -measured : measured;
      if (byTime !== 0) return byTime;
      return order === "desc" ? right.id - left.id : left.id - right.id;
    });
  const items = query.latest ? mergedItems : mergedItems.slice(0, requestedLimit);
  const firstMeta = responses[0]?.data?.meta;

  return {
    data: {
      items,
      meta: {
        count: items.length,
        limit: requestedLimit,
        sort: firstMeta?.sort ?? "measuredAt",
        order,
      },
    },
    response: responses[0]?.response,
  };
}

async function fetchWithTimeout(
  input: RequestInfo | URL,
  init?: RequestInit,
  timeoutMs = backendRequestTimeoutMs,
): Promise<Response> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(input, {
      ...init,
      signal: controller.signal,
      next: { revalidate: 0 },
    } as RequestInit & { next: { revalidate: 0 } });
  } finally {
    clearTimeout(timeout);
  }
}

async function call<T>(
  execute: (headers: HeadersInit) => Promise<ClientResponse<T>>,
  options: ClientOptions = { protected: true },
): Promise<ApiResult<T>> {
  const headers: HeadersInit = {};

  if (options.protected) {
    const apiKey = process.env.AQT_HEALTH_API_KEY;
    if (!apiKey) {
      return {
        ok: false,
        message: "AQT_HEALTH_API_KEY is not configured for protected backend requests.",
      };
    }
    headers.Authorization = `Bearer ${apiKey}`;
  }

  try {
    const { data, error, response } = await execute(headers);

    if (!response?.ok || error) {
      return {
        ok: false,
        status: response?.status,
        message: errorMessage(error, response?.statusText ?? "Backend returned an error."),
      };
    }

    return {
      ok: true,
      data: data as T,
    };
  } catch (error) {
    return {
      ok: false,
      message: error instanceof Error ? error.message : "Backend request failed.",
    };
  }
}

function errorMessage(body: unknown, fallback: string): string {
  if (typeof body === "object" && body !== null && "error" in body) {
    const error = (body as { error?: { message?: unknown; code?: unknown } }).error;
    if (typeof error?.message === "string") return error.message;
    if (typeof error?.code === "string") return error.code;
  }

  if (typeof body === "string" && body.trim()) return body;
  return fallback || "Backend returned an error.";
}
