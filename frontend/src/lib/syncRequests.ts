import { type } from "arktype";

import type { ApiResult, ProviderSyncRequest, ScheduledSyncConfigUpdateRequest } from "./types";

const positiveInteger = type("number.integer > 0").atMost(Number.MAX_SAFE_INTEGER);

const providerSyncBody = type({
  "from?": "string | null",
  "to?": "string | null",
  "dataTypes?": "string[] | null",
  "pageSize?": positiveInteger.or("null"),
});

const scheduledSyncUpdateBody = type({
  "enabled?": "boolean | null",
  "dataTypes?": "string[] | null",
  "cadenceMinutes?": positiveInteger.or("null"),
  "lookbackDays?": positiveInteger.or("null"),
});

// An absent body means "use the defaults"; anything that is present has to parse,
// because silently substituting {} would run the sync over the wrong range.
async function readJsonBody(request: Request): Promise<ApiResult<unknown>> {
  const raw = await request.text();
  if (raw.trim() === "") return { ok: true, data: {} };
  try {
    const data: unknown = JSON.parse(raw);
    if (typeof data !== "object" || data === null || Array.isArray(data)) {
      return { ok: false, status: 400, message: "Request body must be a JSON object." };
    }
    return { ok: true, data };
  } catch {
    return { ok: false, status: 400, message: "Request body is not valid JSON." };
  }
}

export async function readProviderSyncRequest(
  request: Request,
): Promise<ApiResult<ProviderSyncRequest>> {
  const raw = await readJsonBody(request);
  if (!raw.ok) return raw;

  const body = providerSyncBody(raw.data);
  if (body instanceof type.errors) return { ok: false, status: 400, message: body.summary };

  return {
    ok: true,
    data: {
      from: nonEmpty(body.from),
      to: nonEmpty(body.to),
      dataTypes: presentDataTypes(body.dataTypes),
      pageSize: body.pageSize ?? undefined,
    },
  };
}

export async function readScheduledSyncConfigUpdate(
  request: Request,
): Promise<ApiResult<ScheduledSyncConfigUpdateRequest>> {
  const raw = await readJsonBody(request);
  if (!raw.ok) return raw;

  const body = scheduledSyncUpdateBody(raw.data);
  if (body instanceof type.errors) return { ok: false, status: 400, message: body.summary };

  const selectedDataTypes = presentDataTypes(body.dataTypes);
  if (body.dataTypes != null && selectedDataTypes === undefined) {
    return { ok: false, status: 400, message: "dataTypes must include at least one data type." };
  }

  return {
    ok: true,
    data: {
      enabled: body.enabled ?? undefined,
      dataTypes: selectedDataTypes,
      cadenceMinutes: body.cadenceMinutes ?? undefined,
      lookbackDays: body.lookbackDays ?? undefined,
    },
  };
}

function nonEmpty(value?: string | null): string | undefined {
  return value?.trim() || undefined;
}

function presentDataTypes(dataTypes?: string[] | null): string[] | undefined {
  const present = dataTypes?.map((dataType) => dataType.trim()).filter(Boolean);
  return present && present.length > 0 ? present : undefined;
}
