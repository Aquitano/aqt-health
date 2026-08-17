import { DataSection } from "@/components/DataSection";
import { PageHeader } from "@/components/PageHeader";
import { StatusBar } from "@/components/StatusBar";
import { IngestionBatchesTable } from "@/components/tables/IngestionBatchesTable";
import { getIngestionsPageData } from "@/lib/aqtHealthApi";
import { first } from "@/lib/dates";
import styles from "./IngestionsPage.module.css";

type PageProps = {
  searchParams?: Promise<Record<string, string | string[] | undefined>>;
};

export default async function IngestionsPage({ searchParams }: PageProps) {
  const params = (await searchParams) ?? {};
  const limit = first(params.limit) ?? "25";
  const status = first(params.status);
  const data = await getIngestionsPageData({ limit, status });

  return (
    <>
      <PageHeader
        eyebrow="Admin"
        title="Ingestions"
        description="Inspect recent ingestion batches, failures, and stored normalized records."
      />
      <StatusBar apiBaseUrl={data.apiBaseUrl} health={data.health} />
      <form className={styles.filters}>
        <label className={styles.field}>
          <span className={styles.label}>Limit</span>
          <input className={styles.input} name="limit" type="number" min="1" max="500" defaultValue={limit} />
        </label>
        <label className={styles.field}>
          <span className={styles.label}>Status</span>
          <select className={styles.input} name="status" defaultValue={status ?? ""}>
            <option value="">Any</option>
            <option value="processed">Processed</option>
            <option value="failed">Failed</option>
          </select>
        </label>
        <button className={styles.button} type="submit">
          Apply
        </button>
      </form>
      <div className="grid">
        <DataSection title="Recent ingestion batches" result={data.batches}>
          {(response) => (
            <IngestionBatchesTable
              items={response.items}
              getHref={(item) => `/ingestions/${encodeURIComponent(item.id)}`}
            />
          )}
        </DataSection>
        <DataSection title="Recent ingestion failures" result={data.failures}>
          {(response) => (
            <IngestionBatchesTable
              items={response.items}
              getHref={(item) => `/ingestions/${encodeURIComponent(item.id)}`}
            />
          )}
        </DataSection>
      </div>
    </>
  );
}
