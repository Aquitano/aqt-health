import { afterAll, describe, expect, test } from "bun:test";
import { run } from "./cli";

const API_KEY = "test-key";
const seen: URL[] = [];

const server = Bun.serve({
  port: 0,
  fetch(request) {
    const url = new URL(request.url);
    seen.push(url);
    if (request.headers.get("authorization") !== `Bearer ${API_KEY}`) {
      return Response.json({ error: { code: "unauthorized", message: "missing API key" } }, { status: 401 });
    }
    const cursor = url.searchParams.get("cursor");
    switch (url.pathname) {
      case "/api/v2/steps/daily":
        return cursor === null
          ? Response.json({ items: [{ steps: 1 }, { steps: 2 }], meta: { count: 2, nextCursor: "page-2" } })
          : Response.json({ items: [{ steps: 3 }], meta: { count: 1 } });
      case "/api/v2/metrics/heart_rate":
        return Response.json({ items: [{ value: 60 }], meta: { count: 1, nextCursor: "same" } });
      case "/api/v2/health/day":
        return url.searchParams.has("limit")
          ? Response.json({ error: { code: "validation_failed", message: "unknown_parameter: limit" } }, { status: 400 })
          : Response.json({ date: "2026-11-10", steps: { steps: 1 } });
      case "/api/v2/sleep/nights":
        return Response.json(
          { error: { code: "validation_failed", message: "unknown_parameter: fromdate" } },
          { status: 400 },
        );
      case "/openapi":
        return Response.json({
          paths: {
            "/api/v2/steps/daily": {
              get: { summary: "List daily step summaries", parameters: [{ name: "date", in: "query" }] },
            },
            "/api/v2/metrics/{metricType}/daily": {
              get: {
                summary: "Daily aggregates of one metric",
                parameters: [{ name: "metricType", in: "path", required: true, schema: { type: "string" } }],
              },
            },
            "/api/v2/providers/{providerCode}/oauth/callback": { get: { summary: "OAuth callback" } },
            "/api/v2/ingestion/batches": { post: { summary: "Ingest a batch" } },
          },
        });
      default:
        return Response.json({ error: { code: "not_found", message: url.pathname } }, { status: 404 });
    }
  },
});
afterAll(() => server.stop());

async function cli(...argv: string[]) {
  let stdout = "";
  let stderr = "";
  const env = { AQT_HEALTH_URL: server.url.href, AQT_HEALTH_API_KEY: API_KEY };
  const exitCode = await run(argv, env, { out: (text) => (stdout += text), err: (text) => (stderr += text) });
  const parse = (text: string): unknown => (text ? JSON.parse(text) : undefined);
  return { exitCode, json: parse(stdout), error: parse(stderr) };
}

describe("get", () => {
  test("sends name=value pairs as query parameters", async () => {
    const { exitCode, json } = await cli("get", "steps/daily", "fromDate=2026-11-01", "timezone=Europe/Berlin");

    expect(exitCode).toBe(0);
    expect(json).toMatchObject({ items: [{ steps: 1 }, { steps: 2 }] });
    expect(seen.at(-1)?.search).toBe("?fromDate=2026-11-01&timezone=Europe%2FBerlin");
  });

  test("--all follows nextCursor with the original parameters and merges the pages", async () => {
    const { json } = await cli("get", "/api/v2/steps/daily", "fromDate=2026-11-01", "--all");

    expect(json).toEqual({ items: [{ steps: 1 }, { steps: 2 }, { steps: 3 }], meta: { count: 3 } });
    expect(seen.at(-1)?.search).toBe("?fromDate=2026-11-01&cursor=page-2&limit=5000");
  });

  test("--all on an endpoint without pages says so instead of failing on a limit nobody passed", async () => {
    const { exitCode, error } = await cli("get", "health/day", "date=2026-11-10", "--all");

    expect(exitCode).toBe(1);
    expect(error).toMatchObject({ error: { code: "not_paged" } });
  });

  test("--all stops at --max-items and keeps the cursor that continues the read", async () => {
    const { json } = await cli("get", "steps/daily", "--all", "--max-items", "2");

    expect(json).toMatchObject({ meta: { count: 2, nextCursor: "page-2" } });
  });

  test("--all fails on a repeated cursor instead of looping", async () => {
    const { exitCode, error } = await cli("get", "metrics/heart_rate", "--all");

    expect(exitCode).toBe(1);
    expect(error).toMatchObject({ error: { code: "repeated_cursor" } });
  });

  test("an API error is printed as the API's JSON with exit code 1", async () => {
    const { exitCode, json, error } = await cli("get", "sleep/nights", "fromdate=2026-11-01");

    expect(exitCode).toBe(1);
    expect(json).toBeUndefined();
    expect(error).toMatchObject({ error: { code: "validation_failed" } });
  });

  test("an --option that is not a CLI flag is a usage error that points to name=value", async () => {
    const { exitCode, error } = await cli("get", "steps/daily", "--fromDate", "2026-11-01");

    expect(exitCode).toBe(2);
    expect(error).toMatchObject({ error: { message: expect.stringContaining("name=value") } });
  });

  test("refuses OAuth endpoints, which change provider connections", async () => {
    const { exitCode } = await cli("get", "providers/google-health/oauth/callback", "code=x");

    expect(exitCode).toBe(2);
  });
});

describe("endpoints", () => {
  test("lists GET operations other than OAuth", async () => {
    const { json } = await cli("endpoints");

    expect(json).toEqual({
      endpoints: [
        { path: "/api/v2/steps/daily", summary: "List daily step summaries", parameters: ["date"] },
        { path: "/api/v2/metrics/{metricType}/daily", summary: "Daily aggregates of one metric", parameters: ["metricType"] },
      ],
    });
  });

  test("resolves a concrete path to its template and describes its parameters", async () => {
    const { json } = await cli("endpoints", "metrics/heart_rate/daily");

    expect(json).toMatchObject({
      path: "/api/v2/metrics/{metricType}/daily",
      parameters: [{ name: "metricType", in: "path", required: true, schema: { type: "string" } }],
    });
  });
});

test("a missing API key is a usage error", async () => {
  let stderr = "";
  const exitCode = await run(["get", "steps/daily"], {}, { out: () => {}, err: (text) => (stderr += text) });

  expect(exitCode).toBe(2);
  expect(stderr).toContain("AQT_HEALTH_API_KEY");
});
