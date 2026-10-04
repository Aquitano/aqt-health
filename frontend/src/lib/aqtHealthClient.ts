import { type } from "arktype";
import type { ArkErrors } from "arktype";
import createClient from "openapi-fetch";
import type {
  ApiResult,
  ApiSchema,
  ScheduledSyncConfig,
  ScheduledSyncConfigUpdateRequest,
  ScheduledSyncRunResponse,
} from "./types";
import type { paths } from "./generated/aqtHealthApiTypes";
import { serverConfig } from "./serverConfig";

type ClientResponse<T> = {
  data?: T;
  error?: unknown;
  response?: Response;
};

type NextFetchInit = RequestInit & { next: { revalidate: number } };

const backendErrorBody = type({
  error: { "code?": "string", "message?": "string" },
}).or("string");
type BackendErrorBody = typeof backendErrorBody.infer;

type ClientOptions = {
  protected?: boolean;
};

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
const defaultBaseUrl = "http://localhost:8080";
const longRunningBackendRequestTimeoutMs = 300_000;

function apiBaseUrlFromEnv(): string {
  const configured = process.env.AQT_HEALTH_API_BASE_URL;
  if (configured) {
    return configured;
  }
  // Mirror the backend's fail-fast production validation: a production server
  // must not silently fall back to localhost. `next build` prerenders no
  // backend-dependent pages, so the default is only allowed there and in dev.
  if (
    process.env.NODE_ENV === "production" &&
    process.env.NEXT_PHASE !== "phase-production-build"
  ) {
    throw new Error(
      "AQT_HEALTH_API_BASE_URL must be set when running the production server.",
    );
  }
  return defaultBaseUrl;
}

const apiBaseUrl = apiBaseUrlFromEnv();
const rawClient = createClient<paths>({
  baseUrl: apiBaseUrl,
  fetch: (input: Request) => fetchWithTimeout(input),
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
      rawClient.POST(
        "/api/v2/providers/{providerCode}/accounts/{providerInstanceId}/scheduled-sync/run",
        {
          headers,
          params: { path: { providerCode, providerInstanceId } },
          fetch: (input: Request) =>
            fetchWithTimeout(input, undefined, longRunningBackendRequestTimeoutMs),
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

  getLatestProviderSyncJob: (providerCode: ProviderCode) =>
    call<ApiSchema<"ProviderSyncJobStatusResponse">>((headers) =>
      rawClient.GET("/api/v2/providers/{providerCode}/sync-jobs/latest", {
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
    listScalarMetric("respiratory_rate", query),

  listHrvSamples: (query: ScalarSamplesQuery) =>
    listScalarMetric("hrv_rmssd", query),

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

  listBodyMeasurements: (query: Omit<GetQuery<"/api/v2/metrics/samples">, "metricTypes">) =>
    call<ApiSchema<"ScalarSamplesResponse">>((headers) =>
      rawClient.GET("/api/v2/metrics/samples", {
        headers,
        params: { query: { ...query, metricTypes: bodyMetricTypes.join(",") } },
      }),
    ),

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

  listScalarSamples: listScalarMetric,

  listSleepSessions: (query: GetQuery<"/api/v2/sleep/sessions">) =>
    call<ApiSchema<"SleepSessionsResponse">>((headers) =>
      rawClient.GET("/api/v2/sleep/sessions", { headers, params: { query } }),
    ),
};

type ScalarSamplesQuery = GetQuery<"/api/v2/metrics/{metricType}">;

function listScalarMetric(
  metricType: string,
  query: ScalarSamplesQuery,
): Promise<ApiResult<ApiSchema<"ScalarSamplesResponse">>> {
  return call<ApiSchema<"ScalarSamplesResponse">>((headers) =>
    rawClient.GET("/api/v2/metrics/{metricType}", {
      headers,
      params: { path: { metricType }, query },
    }),
  );
}

async function fetchWithTimeout(
  input: RequestInfo | URL,
  init?: RequestInit,
  timeoutMs = serverConfig.backendRequestTimeoutMs,
): Promise<Response> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  const uncachedInit: NextFetchInit = {
    ...init,
    signal: controller.signal,
    next: { revalidate: 0 },
  };
  try {
    return await fetch(input, uncachedInit);
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
        message: errorMessage(backendErrorBody(error), response?.statusText || "Backend returned an error."),
      };
    }

    if (data === undefined) {
      return { ok: false, status: response.status, message: "Backend returned an empty response." };
    }

    return { ok: true, data };
  } catch (error) {
    return {
      ok: false,
      message: error instanceof Error ? error.message : "Backend request failed.",
    };
  }
}

function errorMessage(body: BackendErrorBody | ArkErrors, fallback: string): string {
  if (body instanceof type.errors) return fallback;
  if (typeof body === "string") return body.trim() ? body : fallback;
  return body.error.message ?? body.error.code ?? fallback;
}
