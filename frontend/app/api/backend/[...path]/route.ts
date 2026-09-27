import { NextResponse } from "next/server";
import { aqtHealthClient, toProviderCode } from "@/lib/aqtHealthClient";
import type { ProviderCode } from "@/lib/aqtHealthClient";
import type {
  ApiResult,
  ProviderSyncRequest,
  ScheduledSyncConfigUpdateRequest,
} from "@/lib/types";

// Single server-side proxy for the browser-triggered provider actions. It keeps
// AQT_HEALTH_API_KEY out of the client and only forwards the allowlisted paths below.
// Scheduled-sync runs and long backfill kickoffs can be slow, so the long ceiling
// applies to every proxied call.
export const maxDuration = 300;

type RouteContext = {
  params: Promise<{ path: string[] }>;
};

type ProxyHandler = (
  providerCode: ProviderCode,
  rest: string[],
  request: Request,
) => Promise<ApiResult<unknown>>;

type ProxyRoute = {
  method: "GET" | "POST" | "PUT";
  // Matched against the joined path after /api/backend/; the first capture group
  // must be the provider code, remaining groups are passed to the handler.
  pattern: RegExp;
  successStatus?: number;
  handle: ProxyHandler;
};

const routes: ProxyRoute[] = [
  {
    method: "POST",
    pattern: /^providers\/([^/]+)\/oauth\/start$/,
    handle: (providerCode) => aqtHealthClient.startProviderOAuth(providerCode),
  },
  {
    method: "POST",
    pattern: /^providers\/([^/]+)\/sync-jobs$/,
    successStatus: 202,
    handle: async (providerCode, _rest, request) => {
      const body = await readBody(request);
      return aqtHealthClient.startProviderSyncJob(providerCode, normalizeSyncPayload(body));
    },
  },
  {
    method: "GET",
    pattern: /^providers\/([^/]+)\/sync-jobs\/([^/]+)$/,
    handle: (providerCode, [jobId]) => aqtHealthClient.getProviderSyncJob(providerCode, jobId),
  },
  {
    method: "POST",
    pattern: /^providers\/([^/]+)\/accounts\/([^/]+)\/disconnect$/,
    handle: (providerCode, [providerInstanceId]) =>
      aqtHealthClient.disconnectProviderAccount(providerCode, providerInstanceId),
  },
  {
    method: "POST",
    pattern: /^providers\/([^/]+)\/accounts\/([^/]+)\/reconnect$/,
    handle: (providerCode, [providerInstanceId]) =>
      aqtHealthClient.reconnectProviderAccount(providerCode, providerInstanceId),
  },
  {
    method: "GET",
    pattern: /^providers\/([^/]+)\/accounts\/([^/]+)\/scheduled-sync$/,
    handle: (providerCode, [providerInstanceId]) =>
      aqtHealthClient.getScheduledSyncConfig(providerCode, providerInstanceId),
  },
  {
    method: "PUT",
    pattern: /^providers\/([^/]+)\/accounts\/([^/]+)\/scheduled-sync$/,
    handle: async (providerCode, [providerInstanceId], request) => {
      const body = await readBody(request);
      return aqtHealthClient.updateScheduledSyncConfig(
        providerCode,
        providerInstanceId,
        normalizeScheduledSyncPayload(body),
      );
    },
  },
  {
    method: "POST",
    pattern: /^providers\/([^/]+)\/accounts\/([^/]+)\/scheduled-sync\/run$/,
    handle: (providerCode, [providerInstanceId]) =>
      aqtHealthClient.runScheduledSyncNow(providerCode, providerInstanceId),
  },
];

// Mutating proxy calls are CORS simple requests, so a foreign page could trigger them from the
// user's browser. A cross-site Origin is rejected; same-origin and non-browser callers (curl,
// which sends no Origin) still pass. Only the host is compared: a TLS-terminating reverse proxy
// forwards an https Origin while the app itself is reached over http.
function isCrossSiteRequest(request: Request): boolean {
  const origin = request.headers.get("origin");
  if (!origin) return false;

  const originHost = hostOf(origin);
  const requestHost =
    request.headers.get("x-forwarded-host") ??
    request.headers.get("host") ??
    hostOf(request.url);

  return originHost === undefined || originHost !== requestHost;
}

function hostOf(url: string): string | undefined {
  try {
    return new URL(url).host;
  } catch {
    return undefined;
  }
}

async function dispatch(
  method: ProxyRoute["method"],
  request: Request,
  context: RouteContext,
): Promise<NextResponse> {
  if (method !== "GET" && isCrossSiteRequest(request)) {
    return proxyError(403, "Cross-site requests are not allowed.");
  }

  const { path } = await context.params;
  const joinedPath = path.join("/");

  for (const route of routes) {
    if (route.method !== method) continue;
    const match = joinedPath.match(route.pattern);
    if (!match) continue;

    const providerCode = toProviderCode(match[1]);
    if (!providerCode) {
      return proxyError(404, `Unknown provider '${match[1]}'.`);
    }

    try {
      const result = await route.handle(providerCode, match.slice(2), request);
      return NextResponse.json(result, {
        status: result.ok ? route.successStatus ?? 200 : result.status ?? 500,
      });
    } catch (error) {
      if (error instanceof InvalidPayloadError) return proxyError(400, error.message);
      throw error;
    }
  }

  return proxyError(404, "Unknown backend proxy path.");
}

function proxyError(status: number, message: string): NextResponse {
  return NextResponse.json({ ok: false, status, message }, { status });
}

export function GET(request: Request, context: RouteContext) {
  return dispatch("GET", request, context);
}

export function POST(request: Request, context: RouteContext) {
  return dispatch("POST", request, context);
}

export function PUT(request: Request, context: RouteContext) {
  return dispatch("PUT", request, context);
}

class InvalidPayloadError extends Error {}

async function readBody(request: Request): Promise<Record<string, unknown>> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    throw new InvalidPayloadError("Request body must be valid JSON.");
  }
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    throw new InvalidPayloadError("Request body must be a JSON object.");
  }
  return body as Record<string, unknown>;
}

function normalizeSyncPayload(body: Record<string, unknown>): ProviderSyncRequest {
  return {
    from: nonEmpty(body.from, "from"),
    to: nonEmpty(body.to, "to"),
    dataTypes: dataTypes(body.dataTypes),
    pageSize: positiveInteger(body.pageSize, "pageSize"),
  };
}

function normalizeScheduledSyncPayload(
  body: Record<string, unknown>,
): ScheduledSyncConfigUpdateRequest {
  if (body.enabled != null && typeof body.enabled !== "boolean") {
    throw new InvalidPayloadError("enabled must be a boolean.");
  }
  return {
    enabled: body.enabled ?? undefined,
    dataTypes: dataTypes(body.dataTypes),
    cadenceMinutes: positiveInteger(body.cadenceMinutes, "cadenceMinutes"),
    lookbackDays: positiveInteger(body.lookbackDays, "lookbackDays"),
  };
}

function dataTypes(value: unknown): string[] | undefined {
  if (value == null) return undefined;
  if (
    !Array.isArray(value) ||
    !value.every((item): item is string => typeof item === "string")
  ) {
    throw new InvalidPayloadError("dataTypes must be an array of strings.");
  }
  const items = value.map((item) => item.trim()).filter(Boolean);
  return items.length ? items : undefined;
}

function nonEmpty(value: unknown, field: string): string | undefined {
  if (value == null) return undefined;
  if (typeof value !== "string") throw new InvalidPayloadError(`${field} must be a string.`);
  return value.trim() || undefined;
}

function positiveInteger(value: unknown, field: string): number | undefined {
  if (value == null) return undefined;
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value <= 0) {
    throw new InvalidPayloadError(`${field} must be a positive integer.`);
  }
  return value;
}
