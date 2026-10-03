"use client";

import {
  useEffect,
  useMemo,
  useState,
  useSyncExternalStore,
  useTransition,
} from "react";
import { useRouter } from "next/navigation";
import type {
  ApiResult,
  ProviderSyncRequest,
  ProviderSyncResponse,
  ProviderSyncJobStatusResponse,
} from "@/lib/types";
import { readSyncJobStart, readSyncJobStatus } from "@/lib/apiResponses";
import { proxyFetch } from "./proxyFetch";

const STORAGE_KEY = "aqt-health.provider-sync.active-job";
const CHANGE_EVENT = "aqt-health:sync-job";
let memorySnapshot: string | null | undefined;

type ActiveSyncJob = { providerCode: string; jobId: string };

function snapshot(): string | null {
  if (memorySnapshot !== undefined) return memorySnapshot;
  try {
    return window.localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

function subscribe(onChange: () => void) {
  window.addEventListener("storage", onChange);
  window.addEventListener(CHANGE_EVENT, onChange);
  return () => {
    window.removeEventListener("storage", onChange);
    window.removeEventListener(CHANGE_EVENT, onChange);
  };
}

function store(job: ActiveSyncJob | null) {
  memorySnapshot = job ? JSON.stringify(job) : null;
  try {
    if (memorySnapshot)
      window.localStorage.setItem(STORAGE_KEY, memorySnapshot);
    else window.localStorage.removeItem(STORAGE_KEY);
    memorySnapshot = undefined;
  } catch {
    /* Keep this tab's job active when browser storage is unavailable. */
  }
  window.dispatchEvent(new Event(CHANGE_EVENT));
}

function parse(raw: string | null): ActiveSyncJob | null {
  try {
    const value: unknown = raw ? JSON.parse(raw) : null;
    if (typeof value !== "object" || value === null) return null;
    if (!("providerCode" in value) || !("jobId" in value)) return null;
    return typeof value.providerCode === "string" &&
      typeof value.jobId === "string"
      ? { providerCode: value.providerCode, jobId: value.jobId }
      : null;
  } catch {
    return null;
  }
}

function clearStoredJob(job: ActiveSyncJob) {
  const current = parse(snapshot());
  if (current?.providerCode === job.providerCode && current.jobId === job.jobId)
    store(null);
}

export function isFinishedSyncJob(status: string): boolean {
  return (
    status === "processed" || status === "partial_failed" || status === "failed"
  );
}

export function useProviderSyncJob() {
  const router = useRouter();
  const raw = useSyncExternalStore(subscribe, snapshot, () => null);
  const activeSyncJob = useMemo(() => parse(raw), [raw]);
  const [result, setResult] = useState<ApiResult<ProviderSyncResponse> | null>(
    null
  );
  const [syncJob, setSyncJob] = useState<ProviderSyncJobStatusResponse | null>(
    null
  );
  const [isPending, startTransition] = useTransition();

  useEffect(() => {
    if (!activeSyncJob) return;
    const pollingJob = activeSyncJob;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    async function poll() {
      try {
        const body = await proxyFetch(
          `/providers/${encodeURIComponent(
            pollingJob.providerCode
          )}/sync-jobs/${encodeURIComponent(pollingJob.jobId)}`,
          readSyncJobStatus,
          { signal: controller.signal }
        );
        if (controller.signal.aborted) return;
        if (!body.ok) {
          setResult(body);
          setSyncJob(null);
          clearStoredJob(pollingJob);
          return;
        }
        setSyncJob(body.data);
        if (isFinishedSyncJob(body.data.status)) {
          setResult(
            body.data.summary
              ? { ok: true, data: body.data.summary }
              : {
                  ok: false,
                  message:
                    body.data.errorMessage ?? "Provider sync job failed.",
                }
          );
          clearStoredJob(pollingJob);
          router.refresh();
          return;
        }
        timer = setTimeout(() => void poll(), 1500);
      } catch (error) {
        if (controller.signal.aborted) return;
        setResult({
          ok: false,
          message:
            error instanceof Error
              ? error.message
              : "Provider sync status check failed.",
        });
        setSyncJob(null);
        clearStoredJob(pollingJob);
      }
    }
    void poll();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [activeSyncJob, router]);

  function startSync(providerCode: string, payload: ProviderSyncRequest) {
    setResult(null);
    setSyncJob(null);
    startTransition(async () => {
      try {
        const body = await proxyFetch(
          `/providers/${encodeURIComponent(providerCode)}/sync-jobs`,
          readSyncJobStart,
          {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
          }
        );
        if (body.ok) store({ providerCode, jobId: body.data.jobId });
        else setResult(body);
      } catch (error) {
        setResult({
          ok: false,
          message:
            error instanceof Error
              ? error.message
              : "Provider sync failed. Try again.",
        });
      }
    });
  }

  return {
    activeSyncJob,
    syncJob,
    result,
    isPending,
    startSync,
    clearResult: () => setResult(null),
  };
}
