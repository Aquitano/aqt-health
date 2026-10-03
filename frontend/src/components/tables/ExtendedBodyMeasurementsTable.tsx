import { formatDateTime, formatMeasurement } from "@/lib/format";
import { serverConfig } from "@/lib/serverConfig";
import type { ScalarSample } from "@/lib/types";
import { DataTable, type Column } from "./DataTable";
import { sourceLabel } from "./shared";

const metricLabels = new Map([
  ["fat_mass", "Fat Mass"],
  ["fat_free_mass", "Fat-Free Mass"],
  ["bone_mass", "Bone Mass"],
  ["intracellular_water", "Intracellular Water"],
  ["extracellular_water", "Extracellular Water"],
  ["basal_metabolic_rate", "BMR"],
  ["segmental_fat_mass", "Segmental Fat"],
  ["segmental_muscle_mass", "Segmental Muscle"],
  ["segmental_fat_free_mass", "Segmental Fat-Free"],
]);

function metricLabel(metricType: string): string {
  return metricLabels.get(metricType) ?? metricType;
}

const columns: Column<ScalarSample>[] = [
  { header: "Time", cell: (item) => formatDateTime(item.measuredAt, serverConfig.timeZone) },
  { header: "Metric", cell: (item) => metricLabel(item.metricType) },
  { header: "Value", cell: (item) => formatMeasurement(item.value, item.unit) },
  { header: "Segment", cell: (item) => item.segment ?? "-", muted: true },
  { header: "Source", cell: (item) => sourceLabel(item.source), muted: true },
];

export function ExtendedBodyMeasurementsTable({ items }: { items: ScalarSample[] }) {
  return (
    <DataTable
      items={items}
      columns={columns}
      emptyLabel="No extended body measurements found"
      rowKey={(item) => item.id}
    />
  );
}
