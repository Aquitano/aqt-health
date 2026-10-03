import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type {
  ApiResult,
  ProviderCatalogResponse,
  ProviderDescriptor,
  ProviderStatus,
  ProviderStatusCatalogResponse,
  ProviderSyncJobStatusResponse,
} from "@/lib/types";
import { renderToString } from "react-dom/server";
import { hydrateRoot } from "react-dom/client";
import { ProviderSyncPanel } from "./ProviderSyncPanel";

const mocks = vi.hoisted(() => ({
  refresh: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => mocks,
}));

const SYNC_JOB_STORAGE_KEY = "aqt-health.provider-sync.active-job";

function descriptor(): ProviderDescriptor {
  return {
    providerCode: "google-health",
    displayName: "Google Health",
    authType: "oauth2",
    requiresAuthentication: true,
    supportedDataTypes: ["steps"],
    defaultDataTypes: ["steps"],
    maxSyncRangeDays: 90,
    supportsPageSize: false,
    workflowEndpoints: {
      oauthStart: "/api/v2/providers/google-health/oauth/start",
      oauthCallback: "/api/v2/providers/google-health/oauth/callback",
      accounts: "/api/v2/providers/google-health/accounts",
      disconnect: "/api/v2/providers/google-health/accounts/{id}/disconnect",
      reconnect: "/api/v2/providers/google-health/accounts/{id}/reconnect",
      sync: "/api/v2/providers/google-health/sync",
    },
  };
}

function status(): ProviderStatus {
  return {
    providerCode: "google-health",
    displayName: "Google Health",
    configured: true,
    connected: true,
    needsAuthentication: false,
    canSync: true,
    nextAction: "sync",
    accounts: [],
  };
}

function catalog(): ApiResult<ProviderCatalogResponse> {
  return { ok: true, data: { items: [descriptor()] } };
}

function statuses(): ApiResult<ProviderStatusCatalogResponse> {
  return { ok: true, data: { items: [status()] } };
}

function jobStatus(
  overrides: Partial<ProviderSyncJobStatusResponse> = {},
): ProviderSyncJobStatusResponse {
  return {
    jobId: "job-1",
    providerCode: "google-health",
    requestedFrom: "2026-04-01T00:00:00.000Z",
    requestedTo: "2026-04-02T00:00:00.000Z",
    status: "running",
    totalItems: 2,
    completedItems: 1,
    batchesCount: 0,
    emptyCount: 0,
    errorCount: 0,
    createdAt: "2026-04-01T00:00:00.000Z",
    startedAt: "2026-04-01T00:00:01.000Z",
    updatedAt: "2026-04-01T00:00:10.000Z",
    ...overrides,
  };
}

function renderPanel() {
  return render(
    <ProviderSyncPanel
      catalog={catalog()}
      statuses={statuses()}
      scheduledSyncConfigs={[]}
    />,
  );
}

function storeActiveJob() {
  window.localStorage.setItem(
    SYNC_JOB_STORAGE_KEY,
    JSON.stringify({ providerCode: "google-health", jobId: "job-1" }),
  );
}

describe("ProviderSyncPanel polling", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    cleanup();
    fetchMock.mockReset();
    mocks.refresh.mockReset();
    vi.unstubAllGlobals();
    window.localStorage.clear();
  });

  it("rehydrates the active job from localStorage and polls its status", async () => {
    storeActiveJob();
    fetchMock.mockResolvedValue({
      json: async () => ({ ok: true, data: jobStatus() }),
    });

    renderPanel();

    expect(screen.getByRole("button", { name: /syncing/i })).toBeDisabled();
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        "/api/backend/providers/google-health/sync-jobs/job-1",
        expect.objectContaining({ signal: expect.any(AbortSignal) }),
      );
    });
    await screen.findByText(/1 of 2 windows complete/);
  });

  it("stops polling and clears the stored job when the status check throws", async () => {
    storeActiveJob();
    fetchMock.mockRejectedValue(new Error("network down"));

    renderPanel();

    await screen.findByText(/network down/);
    expect(window.localStorage.getItem(SYNC_JOB_STORAGE_KEY)).toBeNull();
    expect(screen.getByRole("button", { name: "Start sync" })).toBeEnabled();
    expect(mocks.refresh).not.toHaveBeenCalled();
  });

  it("refreshes the router and clears the stored job when the sync finishes", async () => {
    storeActiveJob();
    fetchMock.mockResolvedValue({
      json: async () => ({
        ok: true,
        data: jobStatus({
          status: "processed",
          completedItems: 2,
          finishedAt: "2026-04-01T00:01:00.000Z",
          summary: {
            providerCode: "google-health",
            providerInstanceId: "google-health-me",
            requestedFrom: "2026-04-01T00:00:00.000Z",
            requestedTo: "2026-04-02T00:00:00.000Z",
            status: "processed",
            batches: [],
            emptyDataTypes: [],
            errors: [],
          },
        }),
      }),
    });

    renderPanel();

    await screen.findByText(/Synced 0 batches, created 0 metrics/);
    expect(mocks.refresh).toHaveBeenCalledTimes(1);
    expect(window.localStorage.getItem(SYNC_JOB_STORAGE_KEY)).toBeNull();
    expect(screen.getByRole("button", { name: "Start sync" })).toBeEnabled();
  });

  it("hydrates a saved job without replacing server-rendered markup", async () => {
    storeActiveJob();
    fetchMock.mockReturnValue(new Promise(() => {}));
    const element = <ProviderSyncPanel catalog={catalog()} statuses={statuses()} scheduledSyncConfigs={[]} />;
    const container = document.createElement("div");
    container.innerHTML = renderToString(element);
    expect(container.textContent).toContain("Start sync");
    document.body.append(container);
    const recoverableError = vi.fn();
    let root: ReturnType<typeof hydrateRoot>;
    await act(async () => { root = hydrateRoot(container, element, { onRecoverableError: recoverableError }); });
    expect(container.textContent).toContain("Syncing...");
    expect(recoverableError).not.toHaveBeenCalled();
    act(() => root.unmount());
    container.remove();
  });

  it.each(["processed", "failed", "network"])("preserves another tab's newer job after an old poll is %s", async (outcome) => {
    storeActiveJob();
    let finish!: (value: unknown) => void;
    let fail!: (reason: Error) => void;
    fetchMock.mockReturnValueOnce(new Promise((resolve, reject) => { finish = resolve; fail = reject; }));
    fetchMock.mockReturnValue(new Promise(() => {}));
    renderPanel();
    const newerJob = JSON.stringify({ providerCode: "google-health", jobId: "job-2" });
    window.localStorage.setItem(SYNC_JOB_STORAGE_KEY, newerJob);
    await act(async () => {
      if (outcome === "network") fail(new Error("offline"));
      else finish({ json: async () => outcome === "failed"
        ? { ok: false, message: "expired" }
        : { ok: true, data: jobStatus({ status: "processed" }) } });
    });
    expect(window.localStorage.getItem(SYNC_JOB_STORAGE_KEY)).toBe(newerJob);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      "/api/backend/providers/google-health/sync-jobs/job-2",
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    ));
  });

  it("aborts and ignores a status response after unmount", async () => {
    storeActiveJob();
    let finish!: (value: unknown) => void;
    fetchMock.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    const view = renderPanel();
    const signal = fetchMock.mock.calls[0][1].signal as AbortSignal;
    view.unmount();
    expect(signal.aborted).toBe(true);
    await act(async () => { finish({ json: async () => ({ ok: false, message: "expired" }) }); });
    expect(window.localStorage.getItem(SYNC_JOB_STORAGE_KEY)).not.toBeNull();
    expect(mocks.refresh).not.toHaveBeenCalled();
  });

  it("keeps pending account actions independent", async () => {
    const finishes: ((value: unknown) => void)[] = [];
    fetchMock.mockImplementation(() => new Promise((resolve) => { finishes.push(resolve); }));
    render(<ProviderSyncPanel catalog={catalog()} statuses={{ ok: true, data: { items: [{ ...status(), accounts: [
      { providerInstanceId: "first", status: "connected", tokenStatus: "valid" },
      { providerInstanceId: "second", status: "connected", tokenStatus: "valid" },
    ] }] } }} scheduledSyncConfigs={[]} />);
    const first = screen.getByText("first").closest("div")!.parentElement!;
    const second = screen.getByText("second").closest("div")!.parentElement!;
    fireEvent.click(within(first).getByRole("button", { name: "Run auto now" }));
    fireEvent.click(within(second).getByRole("button", { name: "Run auto now" }));
    await act(async () => { finishes[0]({ json: async () => ({ ok: false, message: "first failed" }) }); });
    expect(within(first).getByRole("button", { name: "Run auto now" })).toBeEnabled();
    expect(within(second).getByRole("button", { name: "Running..." })).toBeDisabled();
    expect(within(second).getByRole("button", { name: "Disconnect" })).toBeDisabled();
    await act(async () => { finishes[1]({ json: async () => ({ ok: false, message: "second failed" }) }); });
  });

});
