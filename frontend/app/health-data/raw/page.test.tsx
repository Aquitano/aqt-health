import { beforeEach, expect, it, vi } from "vitest";
import RawDataPage from "./page";

const mocks = vi.hoisted(() => ({
  scalar: vi.fn(),
  steps: vi.fn(),
  activity: vi.fn(),
}));
vi.mock("@/lib/aqtHealthClient", () => ({
  aqtHealthClient: {
    listScalarSamples: mocks.scalar,
    listDailyStepSummaries: mocks.steps,
    listActivitySummaries: mocks.activity,
  },
}));

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
    cursor: "page-two",
  });
  expect(mocks.steps).not.toHaveBeenCalled();
  expect(mocks.activity).not.toHaveBeenCalled();
});
