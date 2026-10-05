import { expect, it } from "vitest";
import { buildHealthCharts } from "./healthCharts";

const meta = { count: 1, limit: 5000, sort: "date", order: "asc" };

it("keeps daily points on their calendar day and labels instants in the app zone", () => {
  const charts = buildHealthCharts(
    {
      dailySteps: { items: [{ date: "2026-01-05", steps: 900, sampleCount: 3 }], meta },
      bodyMeasurements: {
        items: [{ id: 1, measuredAt: "2026-01-05T20:00:00Z", metricType: "weight", value: 80, unit: "kg" }],
        meta,
      },
    },
    "Pacific/Kiritimati",
  );

  expect(charts.steps.data[0]).toMatchObject({ label: "Jan 5", title: "Jan 5, 2026" });
  expect(charts.weight.data[0]).toMatchObject({ label: "Jan 6", title: "Jan 6, 2026, 10:00 AM" });
});
