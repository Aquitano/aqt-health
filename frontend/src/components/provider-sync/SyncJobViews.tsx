import type {
  ApiResult,
  ProviderSyncResponse,
  ProviderSyncJobStatusResponse,
} from "@/lib/types";
import { ErrorNotice } from "../ErrorNotice";
import { formatStatus } from "./labels";
import styles from "../ProviderSyncPanel.module.css";

export function SyncProgressView({
  job,
}: {
  job: ProviderSyncJobStatusResponse;
}) {
  const completedPercent =
    job.totalItems > 0
      ? Math.round((job.completedItems / job.totalItems) * 100)
      : 0;
  const startedAt = job.startedAt ?? job.createdAt;
  const elapsedSeconds = Math.max(
    0,
    Math.round(
      (new Date(job.updatedAt).getTime() - new Date(startedAt).getTime()) / 1000
    )
  );
  const currentLabel = job.currentItem
    ? `${formatStatus(job.currentItem.dataType)} ${formatWindowLabel(
        new Date(job.currentItem.from),
        new Date(job.currentItem.to)
      )}`
    : "Waiting for backend worker";
  const lastLabel = job.lastCompletedItem
    ? `${formatStatus(job.lastCompletedItem.dataType)} ${formatWindowLabel(
        new Date(job.lastCompletedItem.from),
        new Date(job.lastCompletedItem.to)
      )}`
    : null;

  return (
    <div className={styles.progressPanel}>
      <div className={styles.progressHeader}>
        <div>
          <strong>Sync {formatStatus(job.status)}</strong>
          <span>
            {job.completedItems} of {job.totalItems || "?"} windows complete,{" "}
            {elapsedSeconds}s elapsed
          </span>
        </div>
      </div>
      <div className={styles.progressTrack} aria-label="Provider sync progress">
        <div
          className={styles.progressFill}
          style={{ width: `${completedPercent}%` }}
        />
      </div>
      <div className={styles.progressMeta}>
        <span>{completedPercent}%</span>
        <span>{job.terminal ? "Finished" : currentLabel}</span>
      </div>
      {lastLabel ? <small>Last completed: {lastLabel}</small> : null}
      <small>Job {job.jobId}</small>
    </div>
  );
}

export function SyncResult({
  result,
}: {
  result: ApiResult<ProviderSyncResponse>;
}) {
  if (!result.ok) {
    return <ErrorNotice result={result} />;
  }

  const created = result.data.batches.reduce(
    (sum, batch) =>
      sum +
      Object.values(batch.metricsCreated).reduce(
        (batchSum, count) => batchSum + count,
        0
      ),
    0
  );

  return (
    <div className={styles.result}>
      <strong>
        Synced {result.data.batches.length} batches, created {created} metrics
      </strong>
      <span>
        {result.data.providerCode}: {result.data.requestedFrom} -{" "}
        {result.data.requestedTo}
      </span>
      {result.data.errors.length > 0 ? (
        <ul>
          {result.data.errors.map((error) => (
            <li key={`${error.dataType}-${error.code}`}>
              {error.dataType}: {error.message}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

function formatWindowLabel(from: Date, to: Date): string {
  const formatter = new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  });
  return `${formatter.format(from)} - ${formatter.format(to)}`;
}
