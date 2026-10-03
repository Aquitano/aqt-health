import { formatDateTime, formatMeasurement } from "@/lib/format";
import { scalarMetricLabel } from "@/lib/metrics";
import { serverConfig } from "@/lib/serverConfig";
import type { ScalarSample } from "@/lib/types";
import { DataTable, type Column } from "./DataTable";
import { sourceLabel } from "./shared";

export function ScalarSamplesTable({ items }: { items: ScalarSample[] }) {
  const columns: Column<ScalarSample>[] = [
    { header: "Measured", cell: (item) => formatDateTime(item.measuredAt, serverConfig.timeZone) },
    { header: "Metric", cell: (item) => scalarMetricLabel(item.metricType) },
    { header: "Value", cell: (item) => formatMeasurement(item.value, item.unit) },
    ...(items.some((item) => item.context)
      ? [{ header: "Context", cell: (item: ScalarSample) => item.context, muted: true }]
      : []),
    ...(items.some((item) => item.segment)
      ? [{ header: "Segment", cell: (item: ScalarSample) => item.segment, muted: true }]
      : []),
    { header: "Source", cell: (item) => sourceLabel(item.source), muted: true },
  ];

  return (
    <DataTable
      items={items}
      columns={columns}
      emptyLabel="No samples found."
      rowKey={(item) => item.id}
    />
  );
}
