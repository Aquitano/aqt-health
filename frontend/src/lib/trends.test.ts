import { describe, expect, it } from "vitest";
import { buildTrendStats } from "./trends";

function steps(points: [string, number][]) {
  return buildTrendStats({
    steps: {
      items: points.map(([date, steps]) => ({ date, steps, sampleCount: 1 })),
      meta: { count: points.length, limit: 5000, sort: "date", order: "asc" },
    },
  }, "UTC")[0];
}

describe("trend comparisons", () => {
  it("does not label one-day or two-month changes as weekly changes", () => {
    expect(
      steps([
        ["2026-03-24", 1000],
        ["2026-03-25", 2000],
      ]).change7d
    ).toBeNull();
    const sparse = steps([
      ["2026-01-01", 1000],
      ["2026-03-01", 2000],
    ]);
    expect(sparse.change7d).toBeNull();
    expect(sparse.change30d).toBeNull();
  });

  it("uses the closest baseline within the window, rather than any older point", () => {
    const result = steps([
      ["2026-03-14", 1000],
      ["2026-03-20", 1500],
      ["2026-03-26", 2000],
    ]);
    expect(result.change7d?.abs).toBe(500);
    expect(result.change7d?.pct).toBeCloseTo(100 / 3);
  });

  it("preserves backend daily averages without averaging averages across days", () => {
    const [stat] = buildTrendStats({
      hrv: {
        items: [
          { date: "2026-03-01", count: 6000, avgValue: 40 },
          { date: "2026-03-02", count: 2, avgValue: 60 },
        ],
        meta: { count: 2, limit: 2, sort: "date", order: "asc" },
      },
    }, "UTC");
    expect(stat.points).toEqual([
      { date: "2026-03-01", value: 40 },
      { date: "2026-03-02", value: 60 },
    ]);
    expect(stat.latest).toBe(60);
  });

  it("groups timestamped readings by the app timezone's calendar day", () => {
    const weight = buildTrendStats({
      weight: {
        items: [
          { id: 1, metricType: "weight", measuredAt: "2026-10-04T14:00:00Z", value: 75, unit: "kg" },
          { id: 2, metricType: "weight", measuredAt: "2026-10-05T01:00:00Z", value: 74.6, unit: "kg" },
        ],
        meta: { count: 2, limit: 5000, sort: "measuredAt", order: "asc" },
      },
    }, "America/Los_Angeles").find((stat) => stat.key === "weight");
    expect(weight?.points).toEqual([{ date: "2026-10-04", value: 74.6 }]);
  });
});
