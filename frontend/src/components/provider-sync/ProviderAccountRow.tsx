"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import type {
  ApiResult,
  ProviderAccountStatus,
  ProviderDescriptor,
  ProviderOAuthStartResponse,
  ScheduledSyncConfig,
  ScheduledSyncRunResponse,
} from "@/lib/types";
import { formatDateTime } from "@/lib/format";
import { ErrorNotice } from "../ErrorNotice";
import { formatStatus } from "./labels";
import { proxyFetch } from "./proxyFetch";
import styles from "../ProviderSyncPanel.module.css";

type AccountAction = "disconnect" | "reconnect" | "scheduled" | "run";

export function ProviderAccountRow({
  account,
  descriptor,
  scheduledConfig,
}: {
  account: ProviderAccountStatus;
  descriptor: ProviderDescriptor;
  scheduledConfig?: ScheduledSyncConfig;
}) {
  const router = useRouter();
  const [pendingAction, setPendingAction] = useState<AccountAction | null>(
    null
  );
  const [error, setError] = useState<string | null>(null);
  const [scheduledResult, setScheduledResult] =
    useState<ApiResult<ScheduledSyncRunResponse> | null>(null);
  const base = `/providers/${encodeURIComponent(
    descriptor.providerCode
  )}/accounts/${encodeURIComponent(account.providerInstanceId)}`;

  async function perform(action: AccountAction, execute: () => Promise<void>) {
    if (pendingAction) return;
    setPendingAction(action);
    setError(null);
    setScheduledResult(null);
    try {
      await execute();
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause.message
          : "Account action failed. Try again."
      );
    } finally {
      setPendingAction(null);
    }
  }

  function onAccountAction(action: "disconnect" | "reconnect") {
    void perform(action, async () => {
      const body = await proxyFetch<ProviderOAuthStartResponse>(
        `${base}/${action}`,
        { method: "POST" }
      );
      if (!body.ok) {
        setError(body.message);
        return;
      }
      if (action === "reconnect")
        window.location.assign(body.data.authorizationUrl);
      else router.refresh();
    });
  }

  function onToggleScheduled(enabled: boolean) {
    void perform("scheduled", async () => {
      const body = await proxyFetch<ScheduledSyncConfig>(
        `${base}/scheduled-sync`,
        {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            enabled,
            dataTypes:
              scheduledConfig?.dataTypes ?? descriptor.defaultDataTypes,
            cadenceMinutes: scheduledConfig?.cadenceMinutes ?? 1440,
            lookbackDays: scheduledConfig?.lookbackDays ?? 7,
          }),
        }
      );
      if (body.ok) router.refresh();
      else setError(body.message);
    });
  }

  function onRunScheduled() {
    void perform("run", async () => {
      const body = await proxyFetch<ScheduledSyncRunResponse>(
        `${base}/scheduled-sync/run`,
        { method: "POST" }
      );
      setScheduledResult(body);
      if (body.ok) router.refresh();
    });
  }

  return (
    <div className={styles.accountRow}>
      {error ? <div className={styles.errorNotice}>{error}</div> : null}
      {scheduledResult ? <ScheduledRunResult result={scheduledResult} /> : null}
      <div className={styles.accountIdentity}>
        <strong>{account.providerInstanceId}</strong>
        <span>{formatStatus(account.status)} account</span>
      </div>
      <dl className={styles.accountMeta}>
        <div>
          <dt>Token</dt>
          <dd>{formatStatus(account.tokenStatus)}</dd>
        </div>
        <div>
          <dt>Connected</dt>
          <dd>
            {account.connectedAt
              ? formatDateTime(account.connectedAt)
              : "Never"}
          </dd>
        </div>
        {account.disconnectedAt ? (
          <div>
            <dt>Disconnected</dt>
            <dd>{formatDateTime(account.disconnectedAt)}</dd>
          </div>
        ) : null}
        <div>
          <dt>Last sync</dt>
          <dd>
            {account.lastSyncAt ? formatDateTime(account.lastSyncAt) : "None"}
          </dd>
        </div>
        {account.lastTokenRefreshAt ? (
          <div>
            <dt>Refresh</dt>
            <dd>
              {formatStatus(account.lastTokenRefreshStatus ?? "unknown")}{" "}
              {formatDateTime(account.lastTokenRefreshAt)}
            </dd>
          </div>
        ) : null}
        {account.lastAuthErrorCode ? (
          <div className={styles.accountError}>
            <dt>{account.lastAuthErrorCode}</dt>
            <dd>{account.lastAuthErrorMessage ?? "Authentication failed"}</dd>
          </div>
        ) : null}
        <div className={styles.scheduledMeta}>
          <dt>Automatic</dt>
          <dd>
            {scheduledConfig?.enabled ? "Enabled" : "Paused"}
            {scheduledConfig?.nextRunAt
              ? `, next ${formatDateTime(scheduledConfig.nextRunAt)}`
              : ""}
          </dd>
        </div>
        {scheduledConfig?.lastErrorMessage ? (
          <div className={styles.accountError}>
            <dt>Scheduled sync error</dt>
            <dd>{scheduledConfig.lastErrorMessage}</dd>
          </div>
        ) : null}
      </dl>
      <div className={styles.accountActions}>
        {account.status === "connected" ? (
          <>
            <button
              className={styles.secondaryButton}
              disabled={pendingAction !== null}
              onClick={() => onToggleScheduled(!scheduledConfig?.enabled)}
              type="button"
            >
              {pendingAction === "scheduled"
                ? "Saving..."
                : scheduledConfig?.enabled
                ? "Pause auto"
                : "Enable auto"}
            </button>
            <button
              className={styles.oauthButton}
              disabled={pendingAction !== null}
              onClick={() => onRunScheduled()}
              type="button"
            >
              {pendingAction === "run" ? "Running..." : "Run auto now"}
            </button>
          </>
        ) : null}
        {account.status === "connected" ? (
          <button
            className={styles.secondaryButton}
            disabled={pendingAction !== null}
            onClick={() => onAccountAction("disconnect")}
            type="button"
          >
            {pendingAction === "disconnect" ? "Disconnecting..." : "Disconnect"}
          </button>
        ) : null}
        {account.status === "needs_reauth" ||
        account.status === "disconnected" ? (
          <button
            className={styles.oauthButton}
            disabled={pendingAction !== null}
            onClick={() => onAccountAction("reconnect")}
            type="button"
          >
            {pendingAction === "reconnect" ? "Starting..." : "Reconnect"}
          </button>
        ) : null}
      </div>
    </div>
  );
}

function ScheduledRunResult({
  result,
}: {
  result: ApiResult<ScheduledSyncRunResponse>;
}) {
  if (!result.ok) return <ErrorNotice result={result} />;

  return (
    <div className={styles.result}>
      <strong>Automatic sync {result.data.status}</strong>
      <span>
        {result.data.providerCode}: {result.data.requestedFrom ?? "n/a"} -{" "}
        {result.data.requestedTo ?? "n/a"}
      </span>
      {result.data.errors.length > 0 ? (
        <ul>
          {result.data.errors.map((error) => (
            <li key={error}>{error}</li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
