import { type } from "arktype";
import type { ArkErrors } from "arktype";
import createClient from "openapi-fetch";
import type { ApiResult, ProviderSyncRequest, ScheduledSyncConfigUpdateRequest } from "./types";
import type { paths } from "./generated/aqtHealthApiTypes";
import { serverConfig } from "./serverConfig";

type ClientResponse<T> = {
  data?: T;
  error?: unknown;
  response: Response;
};

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
const longRunningBackendRequestTimeoutMs = 300_000;

const uncachedFetchWithin = (timeoutMs: number) => (request: Request) =>
  fetch(request, { signal: AbortSignal.timeout(timeoutMs), next: { revalidate: 0 } });

const rawClient = createClient<paths>({
  baseUrl: serverConfig.apiBaseUrl,
  fetch: uncachedFetchWithin(serverConfig.backendRequestTimeoutMs),
});

export const aqtHealthClient = {
  getHealth: () => call(() => rawClient.GET("/api/v2/admin/health"), { protected: false }),

  listIngestionBatches: (query: GetQuery<"/api/v2/admin/ingestion/batches">) =>
    call((headers) => rawClient.GET("/api/v2/admin/ingestion/batches", { headers, params: { query } })),

  getIngestionBatch: (id: number) =>
    call((headers) => rawClient.GET("/api/v2/admin/ingestion/batches/{id}", { headers, params: { path: { id } } })),

  listIngestionFailures: (query: GetQuery<"/api/v2/admin/ingestion/failures">) =>
    call((headers) => rawClient.GET("/api/v2/admin/ingestion/failures", { headers, params: { query } })),

  listProviders: () => call((headers) => rawClient.GET("/api/v2/providers", { headers })),

  listProviderStatuses: () => call((headers) => rawClient.GET("/api/v2/providers/status", { headers })),

  startProviderOAuth: (providerCode: ProviderCode) =>
    call((headers) =>
      rawClient.GET("/api/v2/providers/{providerCode}/oauth/start", {
        headers,
        params: { path: { providerCode } },
      }),
    ),

  disconnectProviderAccount: (providerCode: ProviderCode, providerInstanceId: string) =>
    call((headers) =>
      rawClient.POST("/api/v2/providers/{providerCode}/accounts/{providerInstanceId}/disconnect", {
        headers,
        params: { path: { providerCode, providerInstanceId } },
      }),
    ),

  reconnectProviderAccount: (providerCode: ProviderCode, providerInstanceId: string) =>
    call((headers) =>
      rawClient.POST("/api/v2/providers/{providerCode}/accounts/{providerInstanceId}/reconnect", {
        headers,
        params: { path: { providerCode, providerInstanceId } },
      }),
    ),

  getScheduledSyncConfig: (providerCode: ProviderCode, providerInstanceId: string) =>
    call((headers) =>
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
    call((headers) =>
      rawClient.PUT("/api/v2/providers/{providerCode}/accounts/{providerInstanceId}/scheduled-sync", {
        body,
        headers,
        params: { path: { providerCode, providerInstanceId } },
      }),
    ),

  runScheduledSyncNow: (providerCode: ProviderCode, providerInstanceId: string) =>
    call((headers) =>
      rawClient.POST("/api/v2/providers/{providerCode}/accounts/{providerInstanceId}/scheduled-sync/run", {
        headers,
        params: { path: { providerCode, providerInstanceId } },
        fetch: uncachedFetchWithin(longRunningBackendRequestTimeoutMs),
      }),
    ),

  startProviderSyncJob: (providerCode: ProviderCode, body: ProviderSyncRequest) =>
    call((headers) =>
      rawClient.POST("/api/v2/providers/{providerCode}/sync-jobs", {
        body,
        headers,
        params: { path: { providerCode } },
      }),
    ),

  getLatestProviderSyncJob: (providerCode: ProviderCode) =>
    call((headers) =>
      rawClient.GET("/api/v2/providers/{providerCode}/sync-jobs/latest", {
        headers,
        params: { path: { providerCode } },
      }),
    ),

  getProviderSyncJob: (providerCode: ProviderCode, jobId: string) =>
    call((headers) =>
      rawClient.GET("/api/v2/providers/{providerCode}/sync-jobs/{jobId}", {
        headers,
        params: { path: { providerCode, jobId } },
      }),
    ),

  getHealthDay: (query: GetQuery<"/api/v2/health/day">) =>
    call((headers) => rawClient.GET("/api/v2/health/day", { headers, params: { query } })),

  listDailyStepSummaries: (query: GetQuery<"/api/v2/steps/daily">) =>
    call((headers) => rawClient.GET("/api/v2/steps/daily", { headers, params: { query } })),

  listActivitySummaries: (query: GetQuery<"/api/v2/activity/summaries">) =>
    call((headers) => rawClient.GET("/api/v2/activity/summaries", { headers, params: { query } })),

  getScalarDailySummaries: (metricType: string, query: GetQuery<"/api/v2/metrics/{metricType}/daily">) =>
    call((headers) =>
      rawClient.GET("/api/v2/metrics/{metricType}/daily", {
        headers,
        params: { path: { metricType }, query },
      }),
    ),

  listScalarSamples: (metricType: string, query: GetQuery<"/api/v2/metrics/{metricType}">) =>
    call((headers) =>
      rawClient.GET("/api/v2/metrics/{metricType}", {
        headers,
        params: { path: { metricType }, query },
      }),
    ),

  listSleepNights: (query: GetQuery<"/api/v2/sleep/nights">) =>
    call((headers) => rawClient.GET("/api/v2/sleep/nights", { headers, params: { query } })),

  listSleepSummaries: (query: GetQuery<"/api/v2/sleep/summaries">) =>
    call((headers) => rawClient.GET("/api/v2/sleep/summaries", { headers, params: { query } })),

  listBodyMeasurements: (query: Omit<GetQuery<"/api/v2/metrics/samples">, "metricTypes">) =>
    call((headers) =>
      rawClient.GET("/api/v2/metrics/samples", {
        headers,
        params: { query: { ...query, metricTypes: bodyMetricTypes.join(",") } },
      }),
    ),

  getDashboardSummary: (query: GetQuery<"/api/v2/dashboard/summary">) =>
    call((headers) => rawClient.GET("/api/v2/dashboard/summary", { headers, params: { query } })),

  getDashboardTrends: (query: GetQuery<"/api/v2/dashboard/trends">) =>
    call((headers) => rawClient.GET("/api/v2/dashboard/trends", { headers, params: { query } })),

  listBloodPressure: (query: GetQuery<"/api/v2/blood-pressure">) =>
    call((headers) => rawClient.GET("/api/v2/blood-pressure", { headers, params: { query } })),

  listSleepSessions: (query: GetQuery<"/api/v2/sleep/sessions">) =>
    call((headers) => rawClient.GET("/api/v2/sleep/sessions", { headers, params: { query } })),
};

async function call<T>(
  execute: (headers: HeadersInit) => Promise<ClientResponse<T>>,
  options: ClientOptions = { protected: true },
): Promise<ApiResult<T>> {
  const headers: HeadersInit = {};

  if (options.protected) {
    if (!serverConfig.apiKey) {
      return {
        ok: false,
        message: "AQT_HEALTH_API_KEY is not configured for protected backend requests.",
      };
    }
    headers.Authorization = `Bearer ${serverConfig.apiKey}`;
  }

  try {
    const { data, error, response } = await execute(headers);

    if (!response.ok || error) {
      return {
        ok: false,
        status: response.status,
        message: errorMessage(backendErrorBody(error), response.statusText || "Backend returned an error."),
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
