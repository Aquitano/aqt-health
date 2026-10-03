import { describe, expect, it } from "vitest";
import { parseDateRange, startOfDayInstant } from "./dates";

describe("date ranges", () => {
  it("rejects impossible calendar dates", () => {
    expect(
      parseDateRange({ fromDate: "2026-02-30", toDate: "2026-03-02" }, "UTC").warning
    ).toBeDefined();
    expect(
      parseDateRange({ fromDate: "2024-02-29", toDate: "2024-03-01" }, "America/New_York").warning
    ).toBeUndefined();
  });

  it.each([
    ["2026-03-08", "America/New_York", "2026-03-08T05:00:00.000Z"],
    ["2026-03-09", "America/New_York", "2026-03-09T04:00:00.000Z"],
    ["2026-11-01", "America/New_York", "2026-11-01T04:00:00.000Z"],
    ["2026-11-02", "America/New_York", "2026-11-02T05:00:00.000Z"],
    ["2026-03-08", "Asia/Kolkata", "2026-03-07T18:30:00.000Z"],
    ["2018-11-04", "America/Sao_Paulo", "2018-11-04T03:00:00.000Z"],
  ])("finds the first instant of %s in %s", (date, timezone, expected) => {
    expect(startOfDayInstant(date, timezone)).toBe(expected);
  });
});
