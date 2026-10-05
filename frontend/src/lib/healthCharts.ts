import type { ChartSummary } from "@/components/ExpandedChartModal";
import type { HealthChartDatum, HealthChartSeries, ChartPointDetail } from "@/components/charts/HealthMetricChart";
import type {
  ActivitySummariesResponse,
  ActivitySummary,
  ScalarDailySummariesResponse,
  ScalarSample,
  ScalarSamplesResponse,
  SleepNightsResponse,
  SleepSession,
  SleepSummariesResponse,
  SleepSummary,
  StepDailySummariesResponse,
  StepDailySummary,
} from "./types";
import { dateInTimeZone, isDateOnly } from "./dates";
import { formatAxisDate, formatChartValue, formatDateTime, formatFullDate } from "./format";
import { scalarMetricLabel } from "./metrics";

export type NormalizedChart = {
  series: HealthChartSeries[];
  data: HealthChartDatum[];
  details: ChartPointDetail[];
  defaultVisibleMetricKeys: string[];
};

/** A chart point before its display label is resolved. `at` is a date-only day or an ISO instant. */
type ChartPoint = Omit<ChartPointDetail, "atLabel">;

/** One series read from a field of each item; items without a value for it are skipped. */
type FieldSeries<Item> = HealthChartSeries & {
  value: (item: Item) => number | null | undefined;
  hiddenByDefault?: true;
};

type ScalarDailySummary = ScalarDailySummariesResponse["items"][number];

const toHours = (seconds?: number | null) => (seconds == null ? null : seconds / 3600);

const stepsSeries: FieldSeries<StepDailySummary>[] = [
  { key: "steps", label: "Steps", color: "var(--hue-steps)", unit: "steps", value: (item) => item.steps },
];

const activitySeries: FieldSeries<ActivitySummary>[] = [
  {
    key: "distance",
    label: "Distance",
    color: "#eab265",
    unit: "km",
    value: (item) => (item.distanceMeters == null ? null : item.distanceMeters / 1000),
  },
  {
    key: "active_energy",
    label: "Active energy",
    color: "#e87ba0",
    unit: "kcal",
    value: (item) => item.activeEnergyKcal,
    hiddenByDefault: true,
  },
  { key: "active_minutes", label: "Active minutes", color: "#45d6a4", unit: "min", value: (item) => item.activeMinutes },
  {
    key: "average_heart_rate",
    label: "Avg heart rate",
    color: "#f2786d",
    unit: "bpm",
    value: (item) => item.averageHeartRateBpm,
    hiddenByDefault: true,
  },
];

const heartRateSeries: FieldSeries<ScalarDailySummary>[] = [
  { key: "hr_avg", label: "Average", color: "#f2786d", unit: "bpm", value: (item) => item.avgValue },
  { key: "hr_min", label: "Min", color: "#5ec9e8", unit: "bpm", value: (item) => item.minValue },
  { key: "hr_max", label: "Max", color: "#eab265", unit: "bpm", value: (item) => item.maxValue },
];

const sleepSeries: FieldSeries<SleepSession>[] = [
  { key: "sleep", label: "Sleep", color: "var(--hue-sleep)", unit: "h", value: (session) => toHours(session.durationSeconds) },
];

const sleepSummarySeries: FieldSeries<SleepSummary>[] = [
  { key: "sleep_score", label: "Sleep score", color: "#8b9dff", unit: "score", value: (item) => item.sleepScore },
  { key: "sleep_efficiency", label: "Efficiency", color: "#5ec9e8", unit: "%", value: (item) => item.sleepEfficiencyPercent },
  {
    key: "sleep_hours",
    label: "Sleep",
    color: "#45d6a4",
    unit: "h",
    value: (item) => toHours(item.totalSleepSeconds),
    hiddenByDefault: true,
  },
  {
    key: "wakeups",
    label: "Wakeups",
    color: "#eab265",
    unit: "count",
    value: (item) => item.wakeupCount,
    hiddenByDefault: true,
  },
];

/** Keyed by metric type, in legend order. */
const bodyColors = {
  weight: "var(--hue-weight)",
  body_fat: "var(--hue-body-fat)",
  muscle: "var(--hue-muscle)",
  water: "var(--hue-water)",
  visceral_fat: "var(--hue-visceral-fat)",
};

export type HealthCharts = ReturnType<typeof buildHealthCharts>;

export function buildHealthCharts(
  sources: {
    activitySummaries?: ActivitySummariesResponse;
    bodyMeasurements?: ScalarSamplesResponse;
    dailySteps?: StepDailySummariesResponse;
    heartRateDaily?: ScalarDailySummariesResponse;
    hrvSamples?: ScalarSamplesResponse;
    sleepNights?: SleepNightsResponse;
    respiratoryRates?: ScalarSamplesResponse;
    sleepSummaries?: SleepSummariesResponse;
  },
  timeZone: string,
) {
  const body = sampleChart(sources.bodyMeasurements?.items ?? [], bodyColors, timeZone);
  return {
    body,
    weight: detailsToChart(
      body.details.filter((detail) => detail.metricKey === "weight"),
      body.series.filter((series) => series.key === "weight"),
      ["weight"],
      timeZone,
    ),
    steps: fieldChart(
      (sources.dailySteps?.items ?? []).toSorted((a, b) => a.date.localeCompare(b.date)),
      (item) => ({
        id: `${item.date}-${item.source?.providerInstanceId ?? "all"}`,
        at: item.date,
        source: sourceLabel(item.source),
      }),
      stepsSeries,
      timeZone,
    ),
    activity: fieldChart(
      (sources.activitySummaries?.items ?? []).toSorted((a, b) => a.date.localeCompare(b.date) || a.id - b.id),
      (item) => ({ id: item.id, at: item.date, source: sourceLabel(item.source) }),
      activitySeries,
      timeZone,
    ),
    heartRate: fieldChart(
      (sources.heartRateDaily?.items ?? [])
        .filter((day) => day.count > 0)
        .sort((a, b) => a.date.localeCompare(b.date)),
      (day) => ({ id: day.date, at: day.date, source: `${day.count} samples` }),
      heartRateSeries,
      timeZone,
    ),
    sleep: fieldChart(
      (sources.sleepNights?.items ?? [])
        .map((night) => night.session)
        .sort((a, b) => a.startAt.localeCompare(b.startAt)),
      (session) => ({ id: session.id, at: session.startAt, source: sourceLabel(session.source) }),
      sleepSeries,
      timeZone,
    ),
    sleepSummary: fieldChart(
      (sources.sleepSummaries?.items ?? []).toSorted((a, b) => a.startAt.localeCompare(b.startAt) || a.id - b.id),
      (item) => ({ id: item.id, at: item.startAt, source: sourceLabel(item.source) }),
      sleepSummarySeries,
      timeZone,
    ),
    respiratoryRate: sampleChart(sources.respiratoryRates?.items ?? [], { respiratory_rate: "var(--hue-resp)" }, timeZone),
    hrv: sampleChart(sources.hrvSamples?.items ?? [], { hrv_rmssd: "#a3d977" }, timeZone),
  };
}

function fieldChart<Item>(
  items: Item[],
  pointOf: (item: Item) => { id: string | number; at: string; source?: string },
  fields: FieldSeries<Item>[],
  timeZone: string,
): NormalizedChart {
  const points = items.flatMap((item) => {
    const { id, at, source } = pointOf(item);
    return fields.flatMap(({ key, label, unit, value }) => {
      const fieldValue = value(item);
      return fieldValue == null
        ? []
        : [{ id: `${id}-${key}`, at, metricKey: key, label, value: fieldValue, unit, source }];
    });
  });

  return pointsToChart(
    points,
    fields.map(({ key, label, color, unit }) => ({ key, label, color, unit })),
    fields.filter((field) => !field.hiddenByDefault).map((field) => field.key),
    timeZone,
  );
}

/** Charts the metric types in `colors`; each series takes the unit of its first sample. */
function sampleChart(items: ScalarSample[], colors: Record<string, string>, timeZone: string): NormalizedChart {
  const samplesByMetric = Map.groupBy(items, (item) => item.metricType);
  const series = Object.entries(colors).flatMap(([key, color]) => {
    const first = samplesByMetric.get(key)?.[0];
    return first ? [{ key, label: scalarMetricLabel(key), color, unit: first.unit }] : [];
  });
  const charted = new Set(series.map((item) => item.key));
  const points = items
    .filter((item) => charted.has(item.metricType))
    .sort((a, b) => a.measuredAt.localeCompare(b.measuredAt) || a.id - b.id)
    .map((item) => ({
      id: String(item.id),
      at: item.measuredAt,
      metricKey: item.metricType,
      label: scalarMetricLabel(item.metricType),
      value: item.value,
      unit: item.unit,
      source: sourceLabel(item.source),
    }));

  return pointsToChart(points, series, [...charted], timeZone);
}

function pointsToChart(
  points: ChartPoint[],
  series: HealthChartSeries[],
  defaultVisibleMetricKeys: string[],
  timeZone: string,
): NormalizedChart {
  const details = points.map((point) => ({ ...point, atLabel: pointLabel(point.at, timeZone) }));
  return detailsToChart(details, series, defaultVisibleMetricKeys, timeZone);
}

function detailsToChart(
  details: ChartPointDetail[],
  series: HealthChartSeries[],
  defaultVisibleMetricKeys: string[],
  timeZone: string,
): NormalizedChart {
  return {
    series: details.length ? series : [],
    data: detailsToData(details, timeZone),
    details,
    defaultVisibleMetricKeys,
  };
}

function detailsToData(details: ChartPointDetail[], timeZone: string): HealthChartDatum[] {
  const byTimestamp = new Map<string, HealthChartDatum>();
  for (const detail of details) {
    const timestamp = Date.parse(detail.at);
    const id = Number.isNaN(timestamp) ? detail.at : String(timestamp);
    const existing: HealthChartDatum = byTimestamp.get(id) ?? {
      id,
      timestamp: Number.isNaN(timestamp) ? 0 : timestamp,
      label: axisLabel(detail.at, timeZone),
      title: detail.atLabel,
      details: {},
    };
    existing[detail.metricKey] = detail.value;
    existing.details[detail.metricKey] = detail;
    byTimestamp.set(id, existing);
  }

  return Array.from(byTimestamp.values()).sort((a, b) => a.timestamp - b.timestamp);
}

function pointLabel(at: string, timeZone: string): string {
  return isDateOnly(at) ? formatFullDate(at) : formatDateTime(at, timeZone);
}

function axisLabel(at: string, timeZone: string): string {
  if (isDateOnly(at)) return formatAxisDate(at);
  const instant = Date.parse(at);
  return Number.isNaN(instant) ? at : formatAxisDate(dateInTimeZone(instant, timeZone));
}

export function buildSummaries(details: ChartPointDetail[], visibleMetricKeys: string[]): ChartSummary[] {
  const metricKey = visibleMetricKeys.find((key) => details.some((detail) => detail.metricKey === key));
  if (!metricKey) return [];
  const metricDetails = details.filter((detail) => detail.metricKey === metricKey).sort((a, b) => a.at.localeCompare(b.at));
  const values = metricDetails.map((detail) => detail.value);
  const latest = metricDetails[metricDetails.length - 1];
  const oldest = metricDetails[0];
  const unit = latest?.unit;
  const average = values.length ? values.reduce((total, value) => total + value, 0) / values.length : undefined;
  const delta = latest && oldest ? latest.value - oldest.value : undefined;

  return [
    { label: "Metric", value: latest?.label ?? metricKey },
    { label: "Latest", value: latest ? formatChartValue(latest.value, unit) : "n/a" },
    { label: "Min", value: values.length ? formatChartValue(Math.min(...values), unit) : "n/a" },
    { label: "Max", value: values.length ? formatChartValue(Math.max(...values), unit) : "n/a" },
    { label: "Average", value: average !== undefined ? formatChartValue(average, unit) : "n/a" },
    { label: "Delta", value: delta !== undefined ? formatSignedValue(delta, unit) : "n/a" },
  ];
}

function formatSignedValue(value: number, unit?: string): string {
  const sign = value > 0 ? "+" : "";
  return `${sign}${formatChartValue(value, unit)}`;
}

function sourceLabel(source?: { provider: string; providerInstanceId: string } | null): string | undefined {
  if (!source) return undefined;
  return `${source.provider} / ${source.providerInstanceId}`;
}
