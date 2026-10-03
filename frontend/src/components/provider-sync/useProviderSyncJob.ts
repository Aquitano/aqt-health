"use client";

import { useEffect, useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import type {
  ApiResult,
  ProviderSyncRequest,
  ProviderSyncResponse,
  ProviderSyncJobStatusResponse,
} from "@/lib/types";
import { readSyncJobStart, readSyncJobStatus } from "@/lib/apiResponses";
import { proxyFetch } from "./proxyFetch";

type ActiveSyncJob = Pick<ProviderSyncJobStatusResponse, "providerCode" | "jobId">;

export function useProviderSyncJob(runningSyncJob: ProviderSyncJobStatusResponse | null) {
  const router = useRouter();
  const [activeSyncJob, setActiveSyncJob] = useState<ActiveSyncJob | null>(runningSyncJob);
  const [result, setResult] = useState<ApiResult<ProviderSyncResponse> | null>(
    null
  );
  const [syncJob, setSyncJob] = useState<ProviderSyncJobStatusResponse | null>(
    runningSyncJob
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
          setActiveSyncJob(null);
          return;
        }
        setSyncJob(body.data);
        if (body.data.terminal) {
          setResult(
            body.data.summary
              ? { ok: true, data: body.data.summary }
              : {
                  ok: false,
                  message:
                    body.data.errorMessage ?? "Provider sync job failed.",
                }
          );
          setActiveSyncJob(null);
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
        setActiveSyncJob(null);
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
        if (body.ok) setActiveSyncJob({ providerCode, jobId: body.data.jobId });
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
