import Link from "next/link";
import { formatDateTime, formatNumber } from "@/lib/format";
import { serverConfig } from "@/lib/serverConfig";
import type { IngestionBatch } from "@/lib/types";
import { DataTable, type Column } from "./DataTable";
import styles from "./tables.module.css";

function badgeClass(status: string): string {
  switch (status) {
    case "processed":
      return `${styles.badge} ${styles.badgeProcessed}`;
    case "failed":
      return `${styles.badge} ${styles.badgeFailed}`;
    default:
      return `${styles.badge} ${styles.badgeDefault}`;
  }
}

const columns: Column<IngestionBatch>[] = [
  { header: "ID", cell: (item) => <Link href={`/ingestions/${item.id}`}>{item.id}</Link> },
  { header: "Status", cell: (item) => <span className={badgeClass(item.status)}>{item.status}</span> },
  { header: "Provider", cell: (item) => item.providerInstanceId || item.provider, muted: true },
  { header: "Records", cell: (item) => formatNumber(item.recordCount) },
  { header: "Ingested", cell: (item) => formatDateTime(item.ingestedAt, serverConfig.timeZone), muted: true },
  { header: "Error", cell: (item) => item.errorMessage ?? "", muted: true },
];

export function IngestionBatchesTable({ items }: { items: IngestionBatch[] }) {
  return (
    <DataTable
      items={items}
      columns={columns}
      emptyLabel="No ingestion batches found."
      rowKey={(item) => item.id}
    />
  );
}
