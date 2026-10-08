import type {
  ActivitySummariesResponse,
  ScalarSamplesResponse,
  ScalarDailySummariesResponse,
  SleepSummariesResponse,
  StepDailySummariesResponse,
} from "./types";
import { dateInTimeZone } from "./dates";

export type TrendPoint = {
  /** Calendar day, YYYY-MM-DD. */
  date: string;
  value: number;
};

export type TrendChange = {
  abs: number;
  pct: number | null;
};

export type TrendStat = {
  key: string;
  label: string;
  unit: string;
  /** CSS color token, e.g. var(--hue-weight). */
  color: string;
  /** Which direction is healthier, used only to tint the change chips. */
  goodWhen: "up" | "down" | null;
  /** One value per calendar day, ascending. */
  points: TrendPoint[];
  latest: number | null;
  latestAt: string | null;
  average: number | null;
  min: number | null;
  max: number | null;
  change7d: TrendChange | null;
  change30d: TrendChange | null;
};

/** Inputs arrive in ascending timestamp order; retain the final measurement each day. */
function dailyLast(items: TrendPoint[]): TrendPoint[] {
  const byDay = new Map<string, number>();
  for (const item of items) {
    if (Number.isFinite(item.value)) byDay.set(item.date, item.value);
  }
  return Array.from(byDay, ([date, value]) => ({ date, value }))
    .sort((a, b) => a.date.localeCompare(b.date));
}

function dailyAverages(response?: ScalarDailySummariesResponse): TrendPoint[] {
  return (response?.items ?? []).flatMap((item) =>
    item.avgValue != null && Number.isFinite(item.avgValue)
      ? [{ date: item.date, value: item.avgValue }]
      : [],
  );
}

const dayMs = 86_400_000;

const dayStartMs = (date: string) => Date.parse(`${date}T00:00:00Z`);

/** Compare with the closest day near the target, leaving sparse comparisons unavailable. */
function changeOverDays(points: TrendPoint[], days: number): TrendChange | null {
  const latest = points.at(-1);
  if (!latest) return null;
  const targetMs = dayStartMs(latest.date) - days * dayMs;
  const toleranceMs = (days === 7 ? 2 : 7) * dayMs;
  const base = points
    .slice(0, -1)
    .map((point) => ({ point, distance: Math.abs(dayStartMs(point.date) - targetMs) }))
    .filter(({ distance }) => distance <= toleranceMs)
    .sort((a, b) => a.distance - b.distance)[0]?.point;
  if (!base) return null;

  const abs = latest.value - base.value;
  const pct = base.value !== 0 ? (abs / Math.abs(base.value)) * 100 : null;
  return { abs, pct };
}

function summarize(
  config: Pick<TrendStat, "key" | "label" | "unit" | "color" | "goodWhen">,
  points: TrendPoint[],
): TrendStat {
  const values = points.map((point) => point.value);
  const latest = points.at(-1) ?? null;
  return {
    ...config,
    points,
    latest: latest?.value ?? null,
    latestAt: latest?.date ?? null,
    average: values.length ? values.reduce((total, value) => total + value, 0) / values.length : null,
    min: values.length ? Math.min(...values) : null,
    max: values.length ? Math.max(...values) : null,
    change7d: changeOverDays(points, 7),
    change30d: changeOverDays(points, 30),
  };
}

type TrendsInput = {
  weight?: ScalarSamplesResponse;
  steps?: StepDailySummariesResponse;
  sleep?: SleepSummariesResponse;
  hrv?: ScalarDailySummariesResponse;
  activity?: ActivitySummariesResponse;
  respiratory?: ScalarDailySummariesResponse;
};

export function buildTrendStats(input: TrendsInput, timeZone: string): TrendStat[] {
  const dayKey = (isoTimestamp: string) => dateInTimeZone(Date.parse(isoTimestamp), timeZone);
  const weightItems = (input.weight?.items ?? []).filter((item) => item.metricType === "weight");
  const sleepItems = input.sleep?.items ?? [];

  return [
    summarize(
      { key: "weight", label: "Weight", unit: weightItems[0]?.unit ?? "kg", color: "var(--hue-weight)", goodWhen: null },
      dailyLast(weightItems.map((item) => ({ date: dayKey(item.measuredAt), value: item.value }))),
    ),
    summarize(
      { key: "steps", label: "Steps", unit: "steps", color: "var(--hue-steps)", goodWhen: "up" },
      dailyLast((input.steps?.items ?? []).map((item) => ({ date: item.date, value: item.steps }))),
    ),
    summarize(
      { key: "sleep", label: "Sleep", unit: "h", color: "var(--hue-sleep)", goodWhen: "up" },
      dailyLast(
        sleepItems.flatMap((item) =>
          item.totalSleepSeconds == null ? [] : [{ date: dayKey(item.endAt), value: item.totalSleepSeconds / 3600 }],
        ),
      ),
    ),
    summarize(
      { key: "sleep_score", label: "Sleep score", unit: "", color: "var(--hue-score)", goodWhen: "up" },
      dailyLast(
        sleepItems.flatMap((item) =>
          item.sleepScore == null ? [] : [{ date: dayKey(item.endAt), value: item.sleepScore }],
        ),
      ),
    ),
    summarize(
      { key: "hrv", label: "HRV", unit: "ms", color: "var(--hue-hrv)", goodWhen: "up" },
      dailyAverages(input.hrv),
    ),
    summarize(
      { key: "resting_hr", label: "Resting HR", unit: "bpm", color: "var(--hue-heart)", goodWhen: "down" },
      dailyLast(
        (input.activity?.items ?? []).flatMap((item) =>
          item.minHeartRateBpm == null ? [] : [{ date: item.date, value: item.minHeartRateBpm }],
        ),
      ),
    ),
    summarize(
      { key: "respiratory", label: "Respiratory", unit: "rpm", color: "var(--hue-resp)", goodWhen: null },
      dailyAverages(input.respiratory),
    ),
  ].filter((stat) => stat.points.length > 0);
}

/** Short human sentence describing the dominant 30d (or 7d) movement. */
export function insightSentence(stat: TrendStat): string {
  const change = stat.change30d ?? stat.change7d;
  const window = stat.change30d ? "30 days" : "7 days";
  if (!change || stat.latest === null) {
    return `${stat.points.length} day${stat.points.length === 1 ? "" : "s"} of data in range.`;
  }
  if (Math.abs(change.abs) < 1e-6) {
    return `Flat over the last ${window}.`;
  }
  const direction = change.abs > 0 ? "up" : "down";
  const magnitude =
    change.pct !== null
      ? `${Math.abs(change.pct).toFixed(Math.abs(change.pct) >= 10 ? 0 : 1)}%`
      : `${Math.abs(change.abs).toFixed(1)}${stat.unit ? ` ${stat.unit}` : ""}`;
  const verdict =
    stat.goodWhen && stat.goodWhen === direction
      ? " — trending the right way"
      : stat.goodWhen && stat.goodWhen !== direction
        ? " — worth a look"
        : "";
  return `${direction === "up" ? "Up" : "Down"} ${magnitude} over the last ${window}${verdict}.`;
}
