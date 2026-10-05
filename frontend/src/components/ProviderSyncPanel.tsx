"use client";

import { FormEvent, useState, useTransition } from "react";
import type {
  ApiResult,
  ProviderCatalogResponse,
  ProviderDescriptor,
  ProviderStatus,
  ProviderStatusCatalogResponse,
  ProviderSyncJobStatusResponse,
  ScheduledSyncConfig,
} from "@/lib/types";
import { readOAuthStart } from "@/lib/apiResponses";
import { toPositiveInteger } from "@/lib/format";
import { ErrorNotice } from "./ErrorNotice";
import { useProviderSyncJob } from "./provider-sync/useProviderSyncJob";
import { proxyFetch } from "./provider-sync/proxyFetch";
import { ProviderAccountRow } from "./provider-sync/ProviderAccountRow";
import { SyncProgressView, SyncResult } from "./provider-sync/SyncJobViews";
import {
  actionLabel,
  actionDetail,
  primaryOAuthLabel,
  formatStatus,
} from "./provider-sync/labels";
import styles from "./ProviderSyncPanel.module.css";

type ProviderSyncPanelProps = {
  catalog: ApiResult<ProviderCatalogResponse>;
  statuses: ApiResult<ProviderStatusCatalogResponse>;
  scheduledSyncConfigs: ApiResult<ScheduledSyncConfig>[];
  runningSyncJob: ProviderSyncJobStatusResponse | null;
};

type ProviderOption = {
  descriptor: ProviderDescriptor;
  status?: ProviderStatus;
};

export function ProviderSyncPanel({ catalog, statuses, scheduledSyncConfigs, runningSyncJob }: ProviderSyncPanelProps) {
  const [selectedProviderCode, setSelectedProviderCode] = useState("");
  const [oauthError, setOAuthError] = useState<string | null>(null);
  const [isOAuthPending, startOAuthTransition] = useTransition();
  const { activeSyncJob, syncJob, result, isPending, startSync, clearResult } = useProviderSyncJob(runningSyncJob);

  if (!catalog.ok || !statuses.ok) {
    return (
      <section className={styles.panel}>
        <div className={styles.heading}>
          <h2>Provider sync</h2>
        </div>
        {!catalog.ok ? <ErrorNotice result={catalog} /> : null}
        {!statuses.ok ? <ErrorNotice result={statuses} /> : null}
      </section>
    );
  }

  const providers = catalog.data.items.map((descriptor) => ({
    descriptor,
    status: statuses.data.items.find((status) => status.providerCode === descriptor.providerCode),
  }));
  const selectedProvider =
    providers.find((provider) => provider.descriptor.providerCode === selectedProviderCode) ??
    providers[0];
  const canSync = Boolean(selectedProvider?.status?.canSync);
  const scheduledConfigByAccount = new Map(
    scheduledSyncConfigs
      .filter((config) => config.ok)
      .map((config) => [`${config.data.providerCode}:${config.data.providerInstanceId}`, config.data]),
  );

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedProvider || !canSync) return;
    clearResult();

    const formData = new FormData(event.currentTarget);
    const dataTypes = selectedDataTypes(formData);
    const payload = {
      from: toIso(formData.get("from")),
      to: toIso(formData.get("to")),
      dataTypes,
      pageSize: selectedProvider.descriptor.supportsPageSize
        ? toPositiveInteger(formData.get("pageSize"))
        : undefined,
    };

    startSync(selectedProvider.descriptor.providerCode, payload);
  }

  function onStartOAuth() {
    if (!selectedProvider) return;
    clearResult();
    setOAuthError(null);

    startOAuthTransition(async () => {
      const body = await proxyFetch(
        `/providers/${encodeURIComponent(selectedProvider.descriptor.providerCode)}/oauth/start`,
        readOAuthStart,
        { method: "POST" },
      );
      if (body.ok) {
        window.location.assign(body.data.authorizationUrl);
      } else {
        setOAuthError(body.message);
      }
    });
  }

  return (
    <section className={styles.panel}>
      <div className={styles.heading}>
        <h2>Provider sync</h2>
        {selectedProvider?.status ? (
          <span className={styles.statusPill}>{selectedProvider.status.nextAction}</span>
        ) : null}
      </div>

      <div className={styles.providerTabs}>
        {providers.map((provider) => (
          <button
            className={provider === selectedProvider ? styles.providerTabActive : styles.providerTab}
            key={provider.descriptor.providerCode}
            onClick={() => {
              clearResult();
              setSelectedProviderCode(provider.descriptor.providerCode);
            }}
            type="button"
          >
            <span>{provider.descriptor.displayName}</span>
            <small>{provider.status?.nextAction ?? "unknown"}</small>
          </button>
        ))}
      </div>

      {selectedProvider ? (
        <ProviderStatusSummary
          key={selectedProvider.descriptor.providerCode}
          isOAuthPending={isOAuthPending}
          oauthError={oauthError}
          onStartOAuth={onStartOAuth}
          provider={selectedProvider}
          scheduledConfigByAccount={scheduledConfigByAccount}
        />
      ) : null}

      {selectedProvider ? (
        <form className={styles.form} onSubmit={onSubmit}>
          <div className={styles.field}>
            <span className={styles.fieldLabel}>From</span>
            <input className={styles.input} name="from" type="datetime-local" />
          </div>
          <div className={styles.field}>
            <span className={styles.fieldLabel}>To</span>
            <input className={styles.input} name="to" type="datetime-local" />
          </div>
          {selectedProvider.descriptor.supportsPageSize ? (
            <div className={styles.field}>
              <span className={styles.fieldLabel}>Page size</span>
              <input
                className={styles.input}
                name="pageSize"
                type="number"
                min="1"
                max="5000"
                placeholder="default"
              />
            </div>
          ) : null}
          <fieldset className={styles.fieldset} key={selectedProvider.descriptor.providerCode}>
            <legend className={styles.legend}>Data types</legend>
            <div className={styles.checkboxRow}>
              {selectedProvider.descriptor.supportedDataTypes.map((dataType) => (
                <label className={styles.checkboxLabel} key={dataType}>
                  <input
                    defaultChecked={selectedProvider.descriptor.defaultDataTypes.includes(dataType)}
                    name="dataTypes"
                    type="checkbox"
                    value={dataType}
                  />
                  <span>{formatStatus(dataType)}</span>
                </label>
              ))}
            </div>
          </fieldset>
          <button className={styles.submit} type="submit" disabled={isPending || activeSyncJob !== null || !canSync}>
            {isPending || activeSyncJob !== null ? (
              <>
                <span className={styles.spinner} />
                Syncing...
              </>
            ) : (
              "Start sync"
            )}
          </button>
        </form>
      ) : null}

      {syncJob ? <SyncProgressView job={syncJob} /> : null}
      {result ? <SyncResult result={result} /> : null}
    </section>
  );
}

function ProviderStatusSummary({
  isOAuthPending,
  oauthError,
  onStartOAuth,
  provider,
  scheduledConfigByAccount,
}: {
  isOAuthPending: boolean;
  oauthError: string | null;
  onStartOAuth: () => void;
  provider: ProviderOption;
  scheduledConfigByAccount: Map<string, ScheduledSyncConfig>;
}) {
  const status = provider.status;

  if (!status) {
    return (
      <div className={styles.errorNotice}>
        Status is unavailable for {provider.descriptor.displayName}.
      </div>
    );
  }

  return (
    <div className={styles.statusSummary}>
      <div>
        <strong>{actionLabel(provider.descriptor, status)}</strong>
        <span>{actionDetail(provider.descriptor, status)}</span>
      </div>
      <button
        className={styles.oauthButton}
        disabled={!status.configured || isOAuthPending}
        onClick={onStartOAuth}
        type="button"
      >
        {isOAuthPending ? "Starting OAuth..." : primaryOAuthLabel(status)}
      </button>
      {oauthError ? <div className={styles.errorNotice}>{oauthError}</div> : null}
      {status.accounts.length > 0 ? (
        <div className={styles.accountGrid}>
          {status.accounts.map((account) => (
            <ProviderAccountRow
              account={account}
              descriptor={provider.descriptor}
              key={account.providerInstanceId}
              scheduledConfig={scheduledConfigByAccount.get(
                `${provider.descriptor.providerCode}:${account.providerInstanceId}`,
              )}
            />
          ))}
        </div>
      ) : null}
    </div>
  );
}

function selectedDataTypes(formData: FormData): string[] | undefined {
  const dataTypes = formData.getAll("dataTypes").filter((value): value is string => typeof value === "string" && value.trim() !== "");
  return dataTypes.length > 0 ? dataTypes : undefined;
}

function toIso(value: FormDataEntryValue | null): string | undefined {
  if (typeof value !== "string" || !value) return undefined;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return undefined;
  return date.toISOString();
}
