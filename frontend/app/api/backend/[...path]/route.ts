import { NextResponse } from "next/server";
import { aqtHealthClient, toProviderCode } from "@/lib/aqtHealthClient";
import type { ProviderCode } from "@/lib/aqtHealthClient";
import type { ApiResult } from "@/lib/types";
import { readProviderSyncRequest, readScheduledSyncConfigUpdate } from "@/lib/syncRequests";

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
      const body = await readProviderSyncRequest(request);
      if (!body.ok) return body;
      return aqtHealthClient.startProviderSyncJob(providerCode, body.data);
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
      const body = await readScheduledSyncConfigUpdate(request);
      if (!body.ok) return body;
      return aqtHealthClient.updateScheduledSyncConfig(
        providerCode,
        providerInstanceId,
        body.data,
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

    const result = await route.handle(providerCode, match.slice(2), request);
    return NextResponse.json(result, {
      status: result.ok ? route.successStatus ?? 200 : result.status ?? 500,
    });
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
