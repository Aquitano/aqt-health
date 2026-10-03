"use client";

import styles from "./error.module.css";

export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <section className={styles.panel}>
      <h2>Something went wrong</h2>
      <span className={styles.message}>
        An unexpected error occurred.
      </span>
      {error.digest ? (
        <code className={styles.digest}>Error reference: {error.digest}</code>
      ) : null}
      <button className={styles.retry} onClick={reset} type="button">
        Try again
      </button>
    </section>
  );
}
