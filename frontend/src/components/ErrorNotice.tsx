import { CircleAlert } from "lucide-react";
import type { ApiResult } from "@/lib/types";
import styles from "./ErrorNotice.module.css";

type ErrorNoticeProps<T> = {
  result: ApiResult<T>;
};

export function ErrorNotice<T>({ result }: ErrorNoticeProps<T>) {
  if (result.ok) return null;

  return (
    <div className={`${styles.notice} ${styles.error}`}>
      <CircleAlert className={styles.icon} size={16} aria-hidden="true" />
      <span>
        {result.status ? <span className={styles.status}>HTTP {result.status}: </span> : null}
        {result.message}
      </span>
    </div>
  );
}
