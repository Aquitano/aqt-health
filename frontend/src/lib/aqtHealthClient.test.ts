import { afterEach, expect, it, vi } from "vitest";
import { aqtHealthClient } from "./aqtHealthClient";

afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

it.each([
  { extraMetric: false, nextCursor: undefined, truncated: false },
  { extraMetric: true, nextCursor: undefined, truncated: true },
  { extraMetric: false, nextCursor: "more", truncated: true },
])("reports actual body-measurement truncation: %j", async ({ extraMetric, nextCursor, truncated }) => {
  vi.stubEnv("AQT_HEALTH_API_KEY", "test-key");
  vi.stubGlobal("fetch", vi.fn(async (request: Request) => {
    const metric = new URL(request.url).pathname.split("/").at(-1);
    const count = metric === "weight" ? 2 : metric === "body_fat" && extraMetric ? 1 : 0;
    return Response.json({
      items: Array.from({ length: count }, (_, id) => ({ id, measuredAt: "2026-01-01T00:00:00Z" })),
      meta: { count, limit: 2, order: "asc", sort: "measuredAt", nextCursor: metric === "weight" ? nextCursor : undefined },
    });
  }));
  const result = await aqtHealthClient.listBodyMeasurements({ limit: 2 });
  expect(result.ok).toBe(true);
  if (!result.ok) throw new Error(result.message);
  expect(result.data.items).toHaveLength(2);
  expect(result.data.truncated).toBe(truncated);
});
