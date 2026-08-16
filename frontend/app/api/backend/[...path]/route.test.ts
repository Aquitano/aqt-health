import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET, POST, PUT } from "./route";

const mocks = vi.hoisted(() => ({
  startProviderSyncJob: vi.fn(),
  getProviderSyncJob: vi.fn(),
  updateScheduledSyncConfig: vi.fn(),
}));

vi.mock("@/lib/aqtHealthClient", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/aqtHealthClient")>();
  return {
    ...actual,
    aqtHealthClient: {
      startProviderSyncJob: mocks.startProviderSyncJob,
      getProviderSyncJob: mocks.getProviderSyncJob,
      updateScheduledSyncConfig: mocks.updateScheduledSyncConfig,
    },
  };
});

function context(...path: string[]) {
  return { params: Promise.resolve({ path }) };
}

describe("backend proxy route", () => {
  beforeEach(() => {
    mocks.startProviderSyncJob.mockReset();
    mocks.getProviderSyncJob.mockReset();
    mocks.updateScheduledSyncConfig.mockReset();
  });

  it("forwards scheduled-sync updates with a normalized payload", async () => {
    mocks.updateScheduledSyncConfig.mockResolvedValue({
      ok: true,
      data: { enabled: true },
    });

    const response = await PUT(
      new Request(
        "http://frontend.test/api/backend/providers/withings/accounts/withings-me/scheduled-sync",
        {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            enabled: true,
            dataTypes: ["activity", " "],
            cadenceMinutes: 0,
            lookbackDays: 3,
          }),
        },
      ),
      context("providers", "withings", "accounts", "withings-me", "scheduled-sync"),
    );

    expect(response.status).toBe(200);
    expect(mocks.updateScheduledSyncConfig).toHaveBeenCalledWith("withings", "withings-me", {
      enabled: true,
      dataTypes: ["activity"],
      cadenceMinutes: undefined,
      lookbackDays: 3,
    });
  });

  it("forwards allowlisted sync-job creation with a normalized payload", async () => {
    mocks.startProviderSyncJob.mockResolvedValue({
      ok: true,
      data: { jobId: "job-1", status: "queued" },
    });

    const response = await POST(
      new Request("http://frontend.test/api/backend/providers/google-health/sync-jobs", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ from: " ", dataTypes: ["steps", " "], pageSize: 100 }),
      }),
      context("providers", "google-health", "sync-jobs"),
    );

    expect(response.status).toBe(202);
    expect(await response.json()).toEqual({
      ok: true,
      data: { jobId: "job-1", status: "queued" },
    });
    expect(mocks.startProviderSyncJob).toHaveBeenCalledWith("google-health", {
      from: undefined,
      to: undefined,
      dataTypes: ["steps"],
      pageSize: 100,
    });
  });

  it("passes backend error status through", async () => {
    mocks.getProviderSyncJob.mockResolvedValue({
      ok: false,
      status: 404,
      message: "Job not found.",
    });

    const response = await GET(
      new Request("http://frontend.test/api/backend/providers/withings/sync-jobs/missing"),
      context("providers", "withings", "sync-jobs", "missing"),
    );

    expect(response.status).toBe(404);
    expect(mocks.getProviderSyncJob).toHaveBeenCalledWith("withings", "missing");
  });

  it("rejects unknown provider codes without calling the backend", async () => {
    const response = await POST(
      new Request("http://frontend.test/api/backend/providers/fitbit/sync-jobs", {
        method: "POST",
        body: "{}",
      }),
      context("providers", "fitbit", "sync-jobs"),
    );

    expect(response.status).toBe(404);
    expect(mocks.startProviderSyncJob).not.toHaveBeenCalled();
  });

  it("rejects paths outside the allowlist", async () => {
    const response = await GET(
      new Request("http://frontend.test/api/backend/admin/ingestion/batches"),
      context("admin", "ingestion", "batches"),
    );

    expect(response.status).toBe(404);
  });
});
