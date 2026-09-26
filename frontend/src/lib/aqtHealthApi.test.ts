import { beforeEach, describe, expect, it, vi } from "vitest";
import { getHealthDataPageSources, getTrendsPageData } from "./aqtHealthApi";
import { buildTrendStats } from "./trends";

const mocks = vi.hoisted(() => ({
  calls: new Map<string, ReturnType<typeof vi.fn>>(),
}));
vi.mock("./aqtHealthClient", () => ({
  toProviderCode: (value: string) => value,
  aqtHealthClient: new Proxy(
    { apiBaseUrl: "http://test" },
    {
      get(target, property: string) {
        if (property === "apiBaseUrl") return target.apiBaseUrl;
        if (!mocks.calls.has(property)) mocks.calls.set(property, vi.fn());
        return mocks.calls.get(property);
      },
    }
  ),
}));
function response(items: unknown[] = [], nextCursor?: string) {
  return {
    ok: true,
    data: {
      items,
      meta: {
        count: items.length,
        limit: 5000,
        order: "asc",
        sort: "measuredAt",
        nextCursor,
      },
    },
  };
}
function mock(name: string) {
  if (!mocks.calls.has(name)) mocks.calls.set(name, vi.fn());
  return mocks.calls.get(name)!;
}
const methodNames = [
  "getHealth",
  "listScalarSamples",
  "listDailyStepSummaries",
  "listSleepSummaries",
  "getScalarDailySummaries",
  "listActivitySummaries",
  "getDashboardSummary",
  "getDashboardTrends",
  "getHealthDay",
  "listBodyMeasurements",
  "listSleepNights",
  "listRespiratoryRateSamples",
  "listHrvSamples",
  "getLatestActivitySummary",
  "getLatestSleepSummary",
  "getLatestBloodPressure",
];
beforeEach(() => {
  mocks.calls.clear();
  for (const name of methodNames) mock(name).mockResolvedValue(response());
});

describe("page data requests", () => {
  it("loads weight beyond the first 5,000 samples and aggregates high-volume metrics on the backend", async () => {
    const firstPage = Array.from({ length: 5000 }, (_, id) => ({
      id,
      measuredAt: "2026-01-01T12:00:00Z",
      metricType: "weight",
      value: 80,
      unit: "kg",
    }));
    mock("listScalarSamples")
      .mockResolvedValueOnce(response(firstPage, "next-weight"))
      .mockResolvedValueOnce(
        response([
          {
            id: 5001,
            measuredAt: "2026-09-01T12:00:00Z",
            metricType: "weight",
            value: 75,
            unit: "kg",
          },
        ])
      );
    const data = await getTrendsPageData("2026-09-01", 365);
    expect(mock("listScalarSamples")).toHaveBeenLastCalledWith(
      "weight",
      expect.objectContaining({ cursor: "next-weight" })
    );
    expect(
      buildTrendStats({
        weight: data.weight.ok ? data.weight.data : undefined,
      })[0].latest
    ).toBe(75);
    expect(mock("getScalarDailySummaries")).toHaveBeenCalledWith(
      "hrv_rmssd",
      expect.any(Object)
    );
    expect(mock("getScalarDailySummaries")).toHaveBeenCalledWith(
      "respiratory_rate",
      expect.any(Object)
    );
    expect(mock("listBodyMeasurements")).not.toHaveBeenCalled();
  });

  it("propagates a later page failure instead of displaying partial data as complete", async () => {
    mock("listScalarSamples")
      .mockResolvedValueOnce(response([], "next"))
      .mockResolvedValueOnce({ ok: false, status: 503, message: "offline" });
    expect((await getTrendsPageData("2026-09-01", 30)).weight).toEqual({
      ok: false,
      status: 503,
      message: "offline",
    });
  });

  it("uses local-day instants consistently and leaves raw-only datasets unfetched", async () => {
    const sources = getHealthDataPageSources(
      "2026-03-08",
      "2026-03-08",
      "America/New_York"
    );
    await Promise.all(Object.values(sources));
    expect(mock("listBodyMeasurements")).toHaveBeenCalledWith(
      expect.objectContaining({
        from: "2026-03-08T05:00:00.000Z",
        to: "2026-03-09T04:00:00.000Z",
      })
    );
    expect(mock("getScalarDailySummaries")).toHaveBeenCalledWith("heart_rate", {
      from: "2026-03-08T05:00:00.000Z",
      to: "2026-03-09T04:00:00.000Z",
      timezone: "America/New_York",
    });
    expect(mock("listBloodPressure")).not.toHaveBeenCalled();
    expect(mock("listScalarSamples")).not.toHaveBeenCalled();
  });
});
