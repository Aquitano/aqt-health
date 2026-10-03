import { beforeEach, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import RawDataPage from "./page";

const mocks = vi.hoisted(() => ({
  scalar: vi.fn(),
  steps: vi.fn(),
  activity: vi.fn(),
  sessions: vi.fn(),
  summaries: vi.fn(),
}));
vi.mock("@/lib/aqtHealthClient", () => ({
  aqtHealthClient: {
    listScalarSamples: mocks.scalar,
    listDailyStepSummaries: mocks.steps,
    listActivitySummaries: mocks.activity,
    listSleepSessions: mocks.sessions,
    listSleepSummaries: mocks.summaries,
  },
}));
vi.mock("@/components/DateRangeForm", () => ({ DateRangeForm: () => null }));

beforeEach(() => {
  for (const mock of Object.values(mocks)) {
    mock
      .mockReset()
      .mockResolvedValue({
        ok: true,
        data: { items: [], meta: { nextCursor: "next" } },
      });
  }
});

it("fetches only the selected raw metric and forwards its cursor and local-day bounds", async () => {
  await RawDataPage({
    searchParams: Promise.resolve({
      dataset: "heart_rate",
      cursor: "page-two",
      fromDate: "2026-03-08",
      toDate: "2026-03-08",
      timezone: "America/New_York",
    }),
  });
  expect(mocks.scalar).toHaveBeenCalledExactlyOnceWith("heart_rate", {
    from: "2026-03-08T05:00:00.000Z",
    to: "2026-03-09T04:00:00.000Z",
    includeSource: true,
    limit: 100,
    order: "desc",
    sort: "measuredAt",
    raw: true,
    cursor: "page-two",
  });
  expect(mocks.steps).not.toHaveBeenCalled();
  expect(mocks.activity).not.toHaveBeenCalled();
});

it.each([
  { dataset: "sleep-sessions", sort: "startAt", request: mocks.sessions },
  { dataset: "sleep-summaries", sort: "endAt", request: mocks.summaries },
])("explains the start-time filter for $dataset and preserves record pagination", async ({ dataset, sort, request }) => {
  const page = await RawDataPage({
    searchParams: Promise.resolve({
      dataset,
      cursor: "older-records",
      fromDate: "2026-03-08",
      toDate: "2026-03-08",
      timezone: "America/New_York",
    }),
  });
  expect(request).toHaveBeenCalledExactlyOnceWith({
    from: "2026-03-08T05:00:00.000Z",
    to: "2026-03-09T04:00:00.000Z",
    includeSource: true,
    limit: 100,
    order: "desc",
    sort,
    cursor: "older-records",
  });
  expect(renderToStaticMarkup(page)).toContain("Overnight records appear on the day they began.");
  expect(mocks.scalar).not.toHaveBeenCalled();
});
