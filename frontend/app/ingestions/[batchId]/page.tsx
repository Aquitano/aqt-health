import { IngestionBatchDetail } from "@/components/IngestionBatchDetail";
import { PageHeader } from "@/components/PageHeader";
import { StatusBar } from "@/components/StatusBar";
import { getIngestionBatchDetail } from "@/lib/aqtHealthApi";
import { aqtHealthClient } from "@/lib/aqtHealthClient";

type PageProps = {
  params: Promise<{
    batchId: string;
  }>;
};

export default async function IngestionBatchDetailPage({ params }: PageProps) {
  const { batchId } = await params;
  const [health, batch] = await Promise.all([
    aqtHealthClient.getHealth(),
    getIngestionBatchDetail(batchId),
  ]);

  return (
    <>
      <PageHeader
        eyebrow="Ingestion detail"
        title={`Ingestion batch #${batchId}`}
        description="Batch metadata, normalized records, and stored payloads for audit/debugging."
      />
      <StatusBar health={health} />
      <IngestionBatchDetail result={batch} />
    </>
  );
}
