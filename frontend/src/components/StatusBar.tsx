import { formatDateTime } from "@/lib/format";
import { serverConfig } from "@/lib/serverConfig";
import type { ApiResult, HealthResponse } from "@/lib/types";
import styles from "./StatusBar.module.css";

type StatusBarProps = {
  health: ApiResult<HealthResponse>;
  fromDate?: string;
  toDate?: string;
};

export function StatusBar({ health, fromDate, toDate }: StatusBarProps) {
  const status = health.ok ? health.data.status : "offline";
  const serviceTime = health.ok ? formatDateTime(health.data.time, serverConfig.timeZone) : "n/a";

  return (
    <section className={styles.bar} aria-label="Backend status" data-reveal>
      <div className={styles.item}>
        <span className={styles.label}>Status</span>
        <div className={styles.statusItem}>
          <span
            className={`${styles.dot} ${health.ok ? styles.ok : ""}`}
            aria-hidden="true"
          />
          <span className={styles.value}>{status}</span>
        </div>
      </div>
      <div className={styles.item}>
        <span className={styles.label}>API</span>
        <span className={styles.value}>{serverConfig.apiBaseUrl}</span>
      </div>
      {fromDate && toDate ? (
        <div className={styles.item}>
          <span className={styles.label}>Range</span>
          <span className={styles.value}>{fromDate} to {toDate}</span>
        </div>
      ) : null}
      <div className={styles.item}>
        <span className={styles.label}>Backend time</span>
        <span className={styles.value}>{serviceTime}</span>
      </div>
    </section>
  );
}
