import type { ChartSummary } from "@/components/ExpandedChartModal";
import type { HealthChartDatum, HealthChartSeries, ChartPointDetail } from "@/components/charts/HealthMetricChart";
import type { ActivitySummariesResponse, HeartRateDailyPoint, ScalarSample, ScalarSamplesResponse, SleepNightsResponse, SleepSummariesResponse, StepDailySummariesResponse } from "./types";
import { formatAxisDate, formatChartValue } from "./format";

const bodyMetricConfig: Record<string, { label: string; color: string }> = {
  weight: { label: "Weight", color: "var(--hue-weight)" },
  body_fat: { label: "Body fat", color: "var(--hue-body-fat)" },
  muscle: { label: "Muscle", color: "var(--hue-muscle)" },
  water: { label: "Water", color: "var(--hue-water)" },
  visceral_fat: { label: "Visceral fat", color: "var(--hue-visceral-fat)" },
};

const bodyMetricOrder = ["weight", "body_fat", "muscle", "water", "visceral_fat"];

export type NormalizedChart = {
  series: HealthChartSeries[];
  data: HealthChartDatum[];
  details: ChartPointDetail[];
  defaultVisibleMetricKeys: string[];
};

export function buildBodyChart(items: ScalarSample[]): NormalizedChart {
  const supported = items.filter((item) => item.metricType in bodyMetricConfig);
  const presentMetricKeys = bodyMetricOrder.filter((metricKey) =>
    supported.some((item) => item.metricType === metricKey),
  );
  const unitByMetric = new Map<string, string>();
  for (const item of supported) {
    if (!unitByMetric.has(item.metricType)) unitByMetric.set(item.metricType, item.unit);
  }

  const details = measurementsToDetails(supported);
  return {
    series: presentMetricKeys.map((metricKey) => ({
      key: metricKey,
      label: bodyMetricConfig[metricKey].label,
      color: bodyMetricConfig[metricKey].color,
      unit: unitByMetric.get(metricKey),
    })),
    data: detailsToData(details),
    details,
    defaultVisibleMetricKeys: presentMetricKeys,
  };
}

export function buildWeightChart(bodyChart: NormalizedChart): NormalizedChart {
  const details = bodyChart.details.filter((item) => item.metricKey === "weight");
  return detailsToChart(details, bodyChart.series.filter((item) => item.key === "weight"), ["weight"]);
}

export function buildStepsChart(items: StepDailySummariesResponse["items"]): NormalizedChart {
  const details: ChartPointDetail[] = [...items]
    .sort((a, b) => a.date.localeCompare(b.date))
    .map((item) => ({
      id: `steps-${item.date}-${item.source?.providerInstanceId ?? "all"}`,
      at: `${item.date}T12:00:00.000Z`,
      metricKey: "steps",
      label: "Steps",
      value: item.steps,
      unit: "steps",
      source: sourceLabel(item.source),
    }));

  return detailsToChart(details, [{ key: "steps", label: "Steps", color: "var(--hue-steps)", unit: "steps" }], ["steps"]);
}

export function buildActivityChart(items: ActivitySummariesResponse["items"]): NormalizedChart {
  const details: ChartPointDetail[] = [];
  for (const item of [...items].sort((a, b) => a.date.localeCompare(b.date) || a.id - b.id)) {
    const at = `${item.date}T12:00:00.000Z`;
    const source = sourceLabel(item.source);
    if (typeof item.distanceMeters === "number") {
      details.push({
        id: `activity-distance-${item.id}`,
        at,
        metricKey: "distance",
        label: "Distance",
        value: item.distanceMeters / 1000,
        unit: "km",
        source,
      });
    }
    if (typeof item.activeEnergyKcal === "number") {
      details.push({
        id: `activity-energy-${item.id}`,
        at,
        metricKey: "active_energy",
        label: "Active energy",
        value: item.activeEnergyKcal,
        unit: "kcal",
        source,
      });
    }
    if (typeof item.activeMinutes === "number") {
      details.push({
        id: `activity-minutes-${item.id}`,
        at,
        metricKey: "active_minutes",
        label: "Active minutes",
        value: item.activeMinutes,
        unit: "min",
        source,
      });
    }
    if (typeof item.averageHeartRateBpm === "number") {
      details.push({
        id: `activity-avg-hr-${item.id}`,
        at,
        metricKey: "average_heart_rate",
        label: "Avg heart rate",
        value: item.averageHeartRateBpm,
        unit: "bpm",
        source,
      });
    }
  }

  return detailsToChart(
    details,
    [
      { key: "distance", label: "Distance", color: "#eab265", unit: "km" },
      { key: "active_energy", label: "Active energy", color: "#e87ba0", unit: "kcal" },
      { key: "active_minutes", label: "Active minutes", color: "#45d6a4", unit: "min" },
      { key: "average_heart_rate", label: "Avg heart rate", color: "#f2786d", unit: "bpm" },
    ],
    ["distance", "active_minutes"],
  );
}

export function buildHeartRateDailyChart(items: HeartRateDailyPoint[]): NormalizedChart {
  const details: ChartPointDetail[] = [];
  for (const item of [...items].sort((a, b) => a.date.localeCompare(b.date))) {
    const at = `${item.date}T12:00:00.000Z`;
    const source = `${item.count} samples`;
    if (typeof item.avg === "number") {
      details.push({ id: `hr-avg-${item.date}`, at, metricKey: "hr_avg", label: "Average", value: item.avg, unit: "bpm", source });
    }
    if (typeof item.min === "number") {
      details.push({ id: `hr-min-${item.date}`, at, metricKey: "hr_min", label: "Min", value: item.min, unit: "bpm", source });
    }
    if (typeof item.max === "number") {
      details.push({ id: `hr-max-${item.date}`, at, metricKey: "hr_max", label: "Max", value: item.max, unit: "bpm", source });
    }
  }

  return detailsToChart(
    details,
    [
      { key: "hr_avg", label: "Average", color: "#f2786d", unit: "bpm" },
      { key: "hr_min", label: "Min", color: "#5ec9e8", unit: "bpm" },
      { key: "hr_max", label: "Max", color: "#eab265", unit: "bpm" },
    ],
    ["hr_avg", "hr_min", "hr_max"],
  );
}

export function buildSleepChart(sleepNights?: SleepNightsResponse): NormalizedChart {
  const details: ChartPointDetail[] = (sleepNights?.items ?? [])
    .map((night) => night.session)
    .sort((a, b) => a.startAt.localeCompare(b.startAt))
    .map((session) => ({
      id: `sleep-${session.id}`,
      at: session.startAt,
      metricKey: "sleep",
      label: "Sleep",
      value: session.durationSeconds / 3600,
      unit: "h",
      source: sourceLabel(session.source),
    }));

  return detailsToChart(details, [{ key: "sleep", label: "Sleep", color: "var(--hue-sleep)", unit: "h" }], ["sleep"]);
}

export function buildSleepSummaryChart(items: SleepSummariesResponse["items"]): NormalizedChart {
  const details: ChartPointDetail[] = [];
  for (const item of [...items].sort((a, b) => a.startAt.localeCompare(b.startAt) || a.id - b.id)) {
    const source = sourceLabel(item.source);
    if (typeof item.sleepScore === "number") {
      details.push({
        id: `sleep-score-${item.id}`,
        at: item.startAt,
        metricKey: "sleep_score",
        label: "Sleep score",
        value: item.sleepScore,
        unit: "score",
        source,
      });
    }
    if (typeof item.sleepEfficiencyPercent === "number") {
      details.push({
        id: `sleep-efficiency-${item.id}`,
        at: item.startAt,
        metricKey: "sleep_efficiency",
        label: "Efficiency",
        value: item.sleepEfficiencyPercent,
        unit: "%",
        source,
      });
    }
    if (typeof item.totalSleepSeconds === "number") {
      details.push({
        id: `sleep-total-${item.id}`,
        at: item.startAt,
        metricKey: "sleep_hours",
        label: "Sleep",
        value: item.totalSleepSeconds / 3600,
        unit: "h",
        source,
      });
    }
    if (typeof item.wakeupCount === "number") {
      details.push({
        id: `sleep-wakeups-${item.id}`,
        at: item.startAt,
        metricKey: "wakeups",
        label: "Wakeups",
        value: item.wakeupCount,
        unit: "count",
        source,
      });
    }
  }

  return detailsToChart(
    details,
    [
      { key: "sleep_score", label: "Sleep score", color: "#8b9dff", unit: "score" },
      { key: "sleep_efficiency", label: "Efficiency", color: "#5ec9e8", unit: "%" },
      { key: "sleep_hours", label: "Sleep", color: "#45d6a4", unit: "h" },
      { key: "wakeups", label: "Wakeups", color: "#eab265", unit: "count" },
    ],
    ["sleep_score", "sleep_efficiency"],
  );
}

export function buildRespiratoryRateChart(items: ScalarSamplesResponse["items"]): NormalizedChart {
  const details = [...items]
    .sort((a, b) => a.measuredAt.localeCompare(b.measuredAt) || a.id - b.id)
    .map((item) => ({
      id: `respiratory-${item.id}`,
      at: item.measuredAt,
      metricKey: "respiratory_rate",
      label: "Respiratory rate",
      value: item.value,
      unit: item.unit,
      source: sourceLabel(item.source),
    }));

  return detailsToChart(
    details,
    [{ key: "respiratory_rate", label: "Respiratory rate", color: "var(--hue-resp)", unit: items[0]?.unit }],
    ["respiratory_rate"],
  );
}

export function buildHrvChart(items: ScalarSamplesResponse["items"]): NormalizedChart {
  const presentMetricKeys = Array.from(new Set(items.map((item) => item.metricType))).sort();
  const details = [...items]
    .sort((a, b) => a.measuredAt.localeCompare(b.measuredAt) || a.id - b.id)
    .map((item) => ({
      id: `hrv-${item.id}`,
      at: item.measuredAt,
      metricKey: item.metricType,
      label: item.metricType.toUpperCase(),
      value: item.value,
      unit: item.unit,
      source: sourceLabel(item.source),
    }));

  return detailsToChart(
    details,
    presentMetricKeys.map((metricKey, index) => ({
      key: metricKey,
      label: metricKey.toUpperCase(),
      color: ["#a3d977", "#45d6a4", "#8b9dff"][index % 3],
      unit: items.find((item) => item.metricType === metricKey)?.unit,
    })),
    presentMetricKeys.length ? [presentMetricKeys[0]] : [],
  );
}

function measurementsToDetails(items: ScalarSample[]): ChartPointDetail[] {
  return [...items]
    .sort((a, b) => a.measuredAt.localeCompare(b.measuredAt) || a.id - b.id)
    .map((item) => ({
      id: String(item.id),
      at: item.measuredAt,
      metricKey: item.metricType,
      label: bodyMetricConfig[item.metricType]?.label ?? item.metricType,
      value: item.value,
      unit: item.unit,
      source: sourceLabel(item.source),
    }));
}

function detailsToChart(
  details: ChartPointDetail[],
  series: HealthChartSeries[],
  defaultVisibleMetricKeys: string[],
): NormalizedChart {
  return {
    series: details.length ? series : [],
    data: detailsToData(details),
    details,
    defaultVisibleMetricKeys,
  };
}

function detailsToData(details: ChartPointDetail[]): HealthChartDatum[] {
  const byTimestamp = new Map<string, HealthChartDatum>();
  for (const detail of details) {
    const timestamp = Date.parse(detail.at);
    const id = Number.isNaN(timestamp) ? detail.at : String(timestamp);
    const existing: HealthChartDatum = byTimestamp.get(id) ?? {
      id,
      timestamp: Number.isNaN(timestamp) ? 0 : timestamp,
      label: formatAxisDate(detail.at),
      details: {},
    };
    existing[detail.metricKey] = detail.value;
    existing.details[detail.metricKey] = detail;
    byTimestamp.set(id, existing);
  }

  return Array.from(byTimestamp.values()).sort((a, b) => a.timestamp - b.timestamp);
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
