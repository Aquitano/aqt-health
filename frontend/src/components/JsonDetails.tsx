"use client";

import { useEffect, useState } from "react";
import styles from "./JsonDetails.module.css";

type JsonDetailsProps = {
  title: string;
  value: unknown;
};

export function JsonDetails({ title, value }: JsonDetailsProps) {
  const [copied, setCopied] = useState(false);
  const json = JSON.stringify(value, null, 2);

  useEffect(() => {
    if (!copied) return;
    const timeout = setTimeout(() => setCopied(false), 1500);
    return () => clearTimeout(timeout);
  }, [copied]);

  return (
    <details className={styles.details}>
      <summary className={styles.summary}>{title}</summary>
      <div className={styles.content}>
        <div className={styles.header}>
          <span />
          <button
            className={styles.copyBtn}
            type="button"
            onClick={() => void navigator.clipboard.writeText(json).then(() => setCopied(true))}
          >
            {copied ? "Copied" : "Copy"}
          </button>
        </div>
        <pre className={styles.pre}>{json}</pre>
      </div>
    </details>
  );
}
