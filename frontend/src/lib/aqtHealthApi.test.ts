import { beforeEach, describe, expect, it, vi } from "vitest";
import { getHealthDataPageSources, getIngestionsPageData, getTrendsPageData } from "./aqtHealthApi";
import { buildTrendStats } from "./trends";

const mocks = vi.hoisted(() => {
  const names = [
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
    "listBloodPressure",
    "listSleepNights",
    "listRespiratoryRateSamples",
    "listHrvSamples",
    "getLatestActivitySummary",
    "getLatestSleepSummary",
    "getLatestBloodPressure",
    "listIngestionBatches",
    "listIngestionFailures",
  ] as const;
  return Object.fromEntries(names.map((name) => [name, vi.fn()])) as Record<
    (typeof names)[number],
    ReturnType<typeof vi.fn>
  >;
});
vi.mock("./aqtHealthClient", () => ({
  toProviderCode: (value: string) => value,
  aqtHealthClient: { apiBaseUrl: "http://test", ...mocks },
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
beforeEach(() => {
  for (const fn of Object.values(mocks)) fn.mockReset().mockResolvedValue(response());
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
    mocks.listScalarSamples
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
    const data = await getTrendsPageData("2026-09-01", 365, "UTC");
    expect(mocks.listScalarSamples).toHaveBeenLastCalledWith(
      "weight",
      expect.objectContaining({ cursor: "next-weight" })
    );
    expect(
      buildTrendStats({
        weight: data.weight.ok ? data.weight.data : undefined,
      }, "UTC")[0].latest
    ).toBe(75);
    expect(mocks.getScalarDailySummaries).toHaveBeenCalledWith(
      "hrv_rmssd",
      expect.any(Object)
    );
    expect(mocks.getScalarDailySummaries).toHaveBeenCalledWith(
      "respiratory_rate",
      expect.any(Object)
    );
    expect(mocks.listBodyMeasurements).not.toHaveBeenCalled();
  });

  it("propagates a later page failure instead of displaying partial data as complete", async () => {
    mocks.listScalarSamples
      .mockResolvedValueOnce(response([], "next"))
      .mockResolvedValueOnce({ ok: false, status: 503, message: "offline" });
    expect((await getTrendsPageData("2026-09-01", 30, "UTC")).weight).toEqual({
      ok: false,
      status: 503,
      message: "offline",
    });
  });

  it("stops immediately when the backend repeats the requested cursor", async () => {
    mocks.listScalarSamples.mockResolvedValue(response([], "same-page"));
    expect((await getTrendsPageData("2026-09-01", 30, "UTC")).weight).toEqual({
      ok: false,
      message: "The backend repeated a pagination cursor.",
    });
    expect(mocks.listScalarSamples).toHaveBeenCalledTimes(2);
    expect(mocks.listScalarSamples).toHaveBeenLastCalledWith(
      "weight",
      expect.objectContaining({ cursor: "same-page" }),
    );
  });

  it("bounds trends by local days in the app timezone", async () => {
    await getTrendsPageData("2026-03-08", 2, "America/New_York");
    expect(mocks.listScalarSamples).toHaveBeenCalledWith(
      "weight",
      expect.objectContaining({ from: "2026-03-07T05:00:00.000Z", to: "2026-03-09T04:00:00.000Z" })
    );
    expect(mocks.getScalarDailySummaries).toHaveBeenCalledWith("hrv_rmssd", {
      from: "2026-03-07T05:00:00.000Z",
      to: "2026-03-09T04:00:00.000Z",
      timezone: "America/New_York",
    });
  });

  it("filters ingestion batches by the received status", async () => {
    await getIngestionsPageData({ status: "received" });
    expect(mocks.listIngestionBatches).toHaveBeenCalledWith({ limit: 25, status: "received" });
  });

  it("uses local-day instants consistently and leaves raw-only datasets unfetched", async () => {
    const sources = getHealthDataPageSources(
      "2026-03-01",
      "2026-03-08",
      "America/New_York"
    );
    await Promise.all(Object.values(sources));
    expect(mocks.listBodyMeasurements).toHaveBeenCalledWith(
      expect.objectContaining({
        from: "2026-03-01T05:00:00.000Z",
        to: "2026-03-09T04:00:00.000Z",
      })
    );
    expect(mocks.getScalarDailySummaries).toHaveBeenCalledWith("heart_rate", {
      from: "2026-03-01T05:00:00.000Z",
      to: "2026-03-09T04:00:00.000Z",
      timezone: "America/New_York",
    });
    expect(mocks.getDashboardSummary).toHaveBeenCalledWith({
      fromDate: "2026-03-01",
      toDate: "2026-03-08",
      timezone: "America/New_York",
    });
    expect(mocks.getDashboardTrends).toHaveBeenCalledWith({
      periodDays: 8,
      toDate: "2026-03-08",
      timezone: "America/New_York",
    });
    expect(mocks.listBloodPressure).not.toHaveBeenCalled();
    expect(mocks.listScalarSamples).not.toHaveBeenCalled();
  });
});
