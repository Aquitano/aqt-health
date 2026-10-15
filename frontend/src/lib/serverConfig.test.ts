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

it("refuses a production server without its backend key, but lets next build run without one", async () => {
  vi.stubEnv("NODE_ENV", "production");
  vi.stubEnv("AQT_HEALTH_API_BASE_URL", "http://api:8080");
  vi.stubEnv("AQT_HEALTH_API_KEY", undefined);
  await expect(import("./serverConfig")).rejects.toThrow(
    "AQT_HEALTH_API_KEY must be set when running the production server",
  );

  vi.resetModules();
  vi.stubEnv("NEXT_PHASE", "phase-production-build");
  await expect(import("./serverConfig")).resolves.toBeDefined();
});
