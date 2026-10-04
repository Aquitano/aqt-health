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
import { ProviderSyncPanel } from "./ProviderSyncPanel";

const mocks = vi.hoisted(() => ({
  refresh: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => mocks,
}));

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
      sync: "/api/v2/providers/google-health/sync-jobs",
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
    terminal: false,
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

function renderRunningJob() {
  return render(
    <ProviderSyncPanel
      catalog={catalog()}
      statuses={statuses()}
      scheduledSyncConfigs={[]}
      runningSyncJob={jobStatus()}
    />,
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
  });

  it("re-attaches to the running job from the server and polls its status", async () => {
    fetchMock.mockResolvedValue({
      json: async () => ({ ok: true, data: jobStatus({ completedItems: 2, totalItems: 3 }) }),
    });

    renderRunningJob();

    expect(screen.getByRole("button", { name: /syncing/i })).toBeDisabled();
    expect(screen.getByText(/1 of 2 windows complete/)).toBeInTheDocument();
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        "/api/backend/providers/google-health/sync-jobs/job-1",
        expect.objectContaining({ signal: expect.any(AbortSignal) }),
      );
    });
    await screen.findByText(/2 of 3 windows complete/);
  });

  it("stops polling when the status check throws", async () => {
    fetchMock.mockRejectedValue(new Error("network down"));

    renderRunningJob();

    await screen.findByText(/network down/);
    expect(screen.getByRole("button", { name: "Start sync" })).toBeEnabled();
    expect(mocks.refresh).not.toHaveBeenCalled();
  });

  it.each([
    { status: 502, body: "<html>Bad gateway</html>" },
    { status: 204, body: null },
  ])("shows HTTP $status when a poll returns a non-JSON response", async ({ status, body }) => {
    fetchMock.mockResolvedValue(new Response(body, { status }));
    renderRunningJob();
    await screen.findByText("Backend returned an invalid response.");
    expect(screen.getByText(`HTTP ${status}:`)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Start sync" })).toBeEnabled();
  });

  it("refreshes the router when the job turns terminal", async () => {
    fetchMock.mockResolvedValue({
      json: async () => ({
        ok: true,
        data: jobStatus({
          status: "processed",
          terminal: true,
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

    renderRunningJob();

    await screen.findByText(/Synced 0 batches, created 0 metrics/);
    expect(mocks.refresh).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "Start sync" })).toBeEnabled();
  });

  it("adopts a running job supplied by a later refresh while idle", async () => {
    fetchMock.mockReturnValue(new Promise(() => {}));
    const view = render(
      <ProviderSyncPanel catalog={catalog()} statuses={statuses()} scheduledSyncConfigs={[]} runningSyncJob={null} />,
    );
    expect(screen.getByRole("button", { name: "Start sync" })).toBeEnabled();

    view.rerender(
      <ProviderSyncPanel
        catalog={catalog()}
        statuses={statuses()}
        scheduledSyncConfigs={[]}
        runningSyncJob={jobStatus({ jobId: "job-2" })}
      />,
    );

    expect(screen.getByRole("button", { name: /syncing/i })).toBeDisabled();
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        "/api/backend/providers/google-health/sync-jobs/job-2",
        expect.objectContaining({ signal: expect.any(AbortSignal) }),
      );
    });
  });

  it("adopts a job supplied while another was polling once that one finishes", async () => {
    let finishFirst!: (value: unknown) => void;
    fetchMock.mockImplementation((url: string) =>
      url.endsWith("/job-1")
        ? new Promise((resolve) => {
            finishFirst = resolve;
          })
        : new Promise(() => {}),
    );
    const view = renderRunningJob();
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    view.rerender(
      <ProviderSyncPanel
        catalog={catalog()}
        statuses={statuses()}
        scheduledSyncConfigs={[]}
        runningSyncJob={jobStatus({ jobId: "job-2" })}
      />,
    );
    await act(async () => {
      finishFirst({ json: async () => ({ ok: true, data: jobStatus({ status: "failed", terminal: true }) }) });
    });

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        "/api/backend/providers/google-health/sync-jobs/job-2",
        expect.objectContaining({ signal: expect.any(AbortSignal) }),
      );
    });
    expect(screen.getByRole("button", { name: /syncing/i })).toBeDisabled();
  });

  it("aborts and ignores a status response after unmount", async () => {
    let finish!: (value: unknown) => void;
    fetchMock.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    const view = renderRunningJob();
    const signal = fetchMock.mock.calls[0][1].signal as AbortSignal;
    view.unmount();
    expect(signal.aborted).toBe(true);
    await act(async () => { finish({ json: async () => ({ ok: true, data: jobStatus({ status: "processed", terminal: true }) }) }); });
    expect(mocks.refresh).not.toHaveBeenCalled();
  });

  it("keeps pending account actions independent", async () => {
    const finishes: ((value: unknown) => void)[] = [];
    fetchMock.mockImplementation(() => new Promise((resolve) => { finishes.push(resolve); }));
    render(<ProviderSyncPanel catalog={catalog()} statuses={{ ok: true, data: { items: [{ ...status(), accounts: [
      { providerInstanceId: "first", status: "connected", tokenStatus: "valid" },
      { providerInstanceId: "second", status: "connected", tokenStatus: "valid" },
    ] }] } }} scheduledSyncConfigs={[]} runningSyncJob={null} />);
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

  it("shows an enabled schedule without a next run as stopped and resumes it", async () => {
    fetchMock.mockResolvedValue({ json: async () => ({ ok: true, data: { ok: true } }) });
    render(<ProviderSyncPanel catalog={catalog()} statuses={{ ok: true, data: { items: [{ ...status(), accounts: [
      { providerInstanceId: "me", status: "connected", tokenStatus: "valid" },
    ] }] } }} scheduledSyncConfigs={[{ ok: true, data: {
      providerCode: "google-health", providerInstanceId: "me", enabled: true, dataTypes: ["steps"],
      cadenceMinutes: 1440, lookbackDays: 7, failureCount: 3, lastErrorMessage: "steps: account is gone", checkpoints: [],
    } }]} runningSyncJob={null} />);
    expect(screen.getByText("Stopped after errors")).toBeInTheDocument();
    expect(screen.getByText("steps: account is gone")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Resume auto" }));

    await waitFor(() => expect(mocks.refresh).toHaveBeenCalled());
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/backend/providers/google-health/accounts/me/scheduled-sync");
    expect(JSON.parse(init.body)).toMatchObject({ enabled: true });
  });

});
