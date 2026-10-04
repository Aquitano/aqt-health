import type { ReactNode } from "react";
import { ErrorNotice } from "./ErrorNotice";
import type { ApiResult } from "@/lib/types";
import styles from "./DataSection.module.css";

type DataSectionProps<T extends { items: unknown[] }> = {
  title: string;
  result: ApiResult<T>;
  children: (data: T) => ReactNode;
};

export function DataSection<T extends { items: unknown[] }>({ title, result, children }: DataSectionProps<T>) {
  const count = result.ok ? result.data.items.length : undefined;

  return (
    <section className={styles.section}>
      <div className={styles.heading}>
        <h2>{title}</h2>
        {count !== undefined ? <span className={styles.count}>{count}</span> : null}
      </div>
      <ErrorNotice result={result} />
      {result.ok ? children(result.data) : null}
    </section>
  );
}
