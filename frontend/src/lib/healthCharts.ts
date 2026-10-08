import type { ChartSummary } from "@/components/ExpandedChartModal";
import type { HealthChartDatum, HealthChartSeries, ChartPointDetail } from "@/components/charts/HealthMetricChart";
import type { ActivitySummariesResponse, ScalarDailySummariesResponse, ScalarSample, ScalarSamplesResponse, SleepNightsResponse, SleepSummariesResponse, StepDailySummariesResponse } from "./types";
import { dateInTimeZone, isDateOnly } from "./dates";
import { formatAxisDate, formatChartValue, formatDateTime, formatFullDate } from "./format";
import { scalarMetricLabels } from "./metrics";

const bodyMetricConfig: Record<string, { label: string; color: string }> = {
  weight: { label: scalarMetricLabels.weight, color: "var(--hue-weight)" },
  body_fat: { label: scalarMetricLabels.body_fat, color: "var(--hue-body-fat)" },
  muscle: { label: scalarMetricLabels.muscle, color: "var(--hue-muscle)" },
  water: { label: scalarMetricLabels.water, color: "var(--hue-water)" },
  visceral_fat: { label: scalarMetricLabels.visceral_fat, color: "var(--hue-visceral-fat)" },
};

export type NormalizedChart = {
  series: HealthChartSeries[];
  data: HealthChartDatum[];
  details: ChartPointDetail[];
  defaultVisibleMetricKeys: string[];
};

/** A chart point before its display label is resolved. `at` is a date-only day or an ISO instant. */
type ChartPoint = Omit<ChartPointDetail, "atLabel">;

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
  const body = buildBodyChart(sources.bodyMeasurements?.items ?? [], timeZone);
  return {
    body,
    weight: buildWeightChart(body, timeZone),
    steps: buildStepsChart(sources.dailySteps?.items ?? [], timeZone),
    activity: buildActivityChart(sources.activitySummaries?.items ?? [], timeZone),
    heartRate: buildHeartRateDailyChart(sources.heartRateDaily?.items ?? [], timeZone),
    sleep: buildSleepChart(sources.sleepNights, timeZone),
    sleepSummary: buildSleepSummaryChart(sources.sleepSummaries?.items ?? [], timeZone),
    respiratoryRate: buildRespiratoryRateChart(sources.respiratoryRates?.items ?? [], timeZone),
    hrv: buildHrvChart(sources.hrvSamples?.items ?? [], timeZone),
  };
}

function buildBodyChart(items: ScalarSample[], timeZone: string): NormalizedChart {
  const supported = items.filter((item) => item.metricType in bodyMetricConfig);
  const presentMetricKeys = Object.keys(bodyMetricConfig).filter((metricKey) =>
    supported.some((item) => item.metricType === metricKey),
  );
  const unitByMetric = new Map<string, string>();
  for (const item of supported) {
    if (!unitByMetric.has(item.metricType)) unitByMetric.set(item.metricType, item.unit);
  }

  return pointsToChart(
    measurementsToPoints(supported),
    presentMetricKeys.map((metricKey) => ({
      key: metricKey,
      label: bodyMetricConfig[metricKey].label,
      color: bodyMetricConfig[metricKey].color,
      unit: unitByMetric.get(metricKey),
    })),
    presentMetricKeys,
    timeZone,
  );
}

function buildWeightChart(bodyChart: NormalizedChart, timeZone: string): NormalizedChart {
  const details = bodyChart.details.filter((item) => item.metricKey === "weight");
  return detailsToChart(details, bodyChart.series.filter((item) => item.key === "weight"), ["weight"], timeZone);
}

function buildStepsChart(items: StepDailySummariesResponse["items"], timeZone: string): NormalizedChart {
  const points: ChartPoint[] = [...items]
    .sort((a, b) => a.date.localeCompare(b.date))
    .map((item) => ({
      id: `steps-${item.date}-${item.source?.providerInstanceId ?? "all"}`,
      at: item.date,
      metricKey: "steps",
      label: "Steps",
      value: item.steps,
      unit: "steps",
      source: sourceLabel(item.source),
    }));

  return pointsToChart(points, [{ key: "steps", label: "Steps", color: "var(--hue-steps)", unit: "steps" }], ["steps"], timeZone);
}

function buildActivityChart(items: ActivitySummariesResponse["items"], timeZone: string): NormalizedChart {
  const points: ChartPoint[] = [];
  for (const item of [...items].sort((a, b) => a.date.localeCompare(b.date) || a.id - b.id)) {
    const at = item.date;
    const source = sourceLabel(item.source);
    if (typeof item.distanceMeters === "number") {
      points.push({
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
      points.push({
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
      points.push({
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
      points.push({
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

  return pointsToChart(
    points,
    [
      { key: "distance", label: "Distance", color: "#eab265", unit: "km" },
      { key: "active_energy", label: "Active energy", color: "#e87ba0", unit: "kcal" },
      { key: "active_minutes", label: "Active minutes", color: "#45d6a4", unit: "min" },
      { key: "average_heart_rate", label: "Avg heart rate", color: "#f2786d", unit: "bpm" },
    ],
    ["distance", "active_minutes"],
    timeZone,
  );
}

function buildHeartRateDailyChart(items: ScalarDailySummariesResponse["items"], timeZone: string): NormalizedChart {
  const points: ChartPoint[] = [];
  for (const item of items.filter((day) => day.count > 0).sort((a, b) => a.date.localeCompare(b.date))) {
    const at = item.date;
    const source = `${item.count} samples`;
    if (typeof item.avgValue === "number") {
      points.push({ id: `hr-avg-${item.date}`, at, metricKey: "hr_avg", label: "Average", value: item.avgValue, unit: "bpm", source });
    }
    if (typeof item.minValue === "number") {
      points.push({ id: `hr-min-${item.date}`, at, metricKey: "hr_min", label: "Min", value: item.minValue, unit: "bpm", source });
    }
    if (typeof item.maxValue === "number") {
      points.push({ id: `hr-max-${item.date}`, at, metricKey: "hr_max", label: "Max", value: item.maxValue, unit: "bpm", source });
    }
  }

  return pointsToChart(
    points,
    [
      { key: "hr_avg", label: "Average", color: "#f2786d", unit: "bpm" },
      { key: "hr_min", label: "Min", color: "#5ec9e8", unit: "bpm" },
      { key: "hr_max", label: "Max", color: "#eab265", unit: "bpm" },
    ],
    ["hr_avg", "hr_min", "hr_max"],
    timeZone,
  );
}

function buildSleepChart(sleepNights: SleepNightsResponse | undefined, timeZone: string): NormalizedChart {
  const points: ChartPoint[] = (sleepNights?.items ?? [])
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

  return pointsToChart(points, [{ key: "sleep", label: "Sleep", color: "var(--hue-sleep)", unit: "h" }], ["sleep"], timeZone);
}

function buildSleepSummaryChart(items: SleepSummariesResponse["items"], timeZone: string): NormalizedChart {
  const points: ChartPoint[] = [];
  for (const item of [...items].sort((a, b) => a.startAt.localeCompare(b.startAt) || a.id - b.id)) {
    const source = sourceLabel(item.source);
    if (typeof item.sleepScore === "number") {
      points.push({
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
      points.push({
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
      points.push({
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
      points.push({
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

  return pointsToChart(
    points,
    [
      { key: "sleep_score", label: "Sleep score", color: "#8b9dff", unit: "score" },
      { key: "sleep_efficiency", label: "Efficiency", color: "#5ec9e8", unit: "%" },
      { key: "sleep_hours", label: "Sleep", color: "#45d6a4", unit: "h" },
      { key: "wakeups", label: "Wakeups", color: "#eab265", unit: "count" },
    ],
    ["sleep_score", "sleep_efficiency"],
    timeZone,
  );
}

function buildRespiratoryRateChart(items: ScalarSamplesResponse["items"], timeZone: string): NormalizedChart {
  const points = [...items]
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

  return pointsToChart(
    points,
    [{ key: "respiratory_rate", label: "Respiratory rate", color: "var(--hue-resp)", unit: items[0]?.unit }],
    ["respiratory_rate"],
    timeZone,
  );
}

function buildHrvChart(items: ScalarSamplesResponse["items"], timeZone: string): NormalizedChart {
  const presentMetricKeys = Array.from(new Set(items.map((item) => item.metricType))).sort();
  const points = [...items]
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

  return pointsToChart(
    points,
    presentMetricKeys.map((metricKey, index) => ({
      key: metricKey,
      label: metricKey.toUpperCase(),
      color: ["#a3d977", "#45d6a4", "#8b9dff"][index % 3],
      unit: items.find((item) => item.metricType === metricKey)?.unit,
    })),
    presentMetricKeys.length ? [presentMetricKeys[0]] : [],
    timeZone,
  );
}

function measurementsToPoints(items: ScalarSample[]): ChartPoint[] {
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
