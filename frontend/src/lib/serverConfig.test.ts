import { afterEach, expect, it, vi } from "vitest";

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

it("defaults the app timezone to UTC and rejects names that are not IANA zones", async () => {
  vi.stubEnv("AQT_HEALTH_TIMEZONE", undefined);
  expect((await import("./serverConfig")).serverConfig.timeZone).toBe("UTC");

  vi.resetModules();
  vi.stubEnv("AQT_HEALTH_TIMEZONE", "Mars/Olympus_Mons");
  await expect(import("./serverConfig")).rejects.toThrow("AQT_HEALTH_TIMEZONE must be an IANA time zone");
});
