import { describe, expect, it } from "vitest";

import { formatDateTime } from "./format";

describe("formatDateTime", () => {
  it("falls back for invalid numeric timestamps", () => {
    expect(formatDateTime(Number.NaN, "UTC")).toBe("n/a");
  });

  it("renders the instant in the given zone", () => {
    expect(formatDateTime("2026-01-01T23:30:00Z", "Asia/Tokyo")).toBe("Jan 2, 2026, 8:30 AM");
  });
});
