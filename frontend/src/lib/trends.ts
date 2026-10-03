import type {
  ActivitySummariesResponse,
  ScalarSamplesResponse,
  ScalarDailySummariesResponse,
  SleepSummariesResponse,
  StepDailySummariesResponse,
} from "./types";

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

function dayKey(isoTimestamp: string): string {
  return isoTimestamp.slice(0, 10);
}

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

/** Compare with the closest day near the target, leaving sparse comparisons unavailable. */
function changeOverDays(points: TrendPoint[], days: number): TrendChange | null {
  if (points.length < 2) return null;
  const latest = points[points.length - 1];
  const latestMs = Date.parse(`${latest.date}T00:00:00Z`);
  const targetMs = latestMs - days * 86_400_000;
  const toleranceMs = (days === 7 ? 2 : 7) * 86_400_000;
  let base: TrendPoint | null = null;
  let closestDistance = Infinity;
  for (let i = 0; i < points.length - 1; i += 1) {
    const distance = Math.abs(Date.parse(`${points[i].date}T00:00:00Z`) - targetMs);
    if (distance <= toleranceMs && distance < closestDistance) {
      base = points[i];
      closestDistance = distance;
    }
  }
  if (!base) return null;

  const abs = latest.value - base.value;
  const pct = base.value !== 0 ? (abs / Math.abs(base.value)) * 100 : null;
  return { abs, pct };
}

function summarize(
  config: { key: string; label: string; unit: string; color: string; goodWhen: "up" | "down" | null },
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

export type TrendsInput = {
  weight?: ScalarSamplesResponse;
  steps?: StepDailySummariesResponse;
  sleep?: SleepSummariesResponse;
  hrv?: ScalarDailySummariesResponse;
  activity?: ActivitySummariesResponse;
  respiratory?: ScalarDailySummariesResponse;
};

export function buildTrendStats(input: TrendsInput): TrendStat[] {
  const stats: TrendStat[] = [];

  const weightItems = (input.weight?.items ?? []).filter((item) => item.metricType === "weight");
  const weightUnit = weightItems[0]?.unit ?? "kg";
  stats.push(
    summarize(
      { key: "weight", label: "Weight", unit: weightUnit, color: "var(--hue-weight)", goodWhen: null },
      dailyLast(
        weightItems.map((item) => ({ date: dayKey(item.measuredAt), value: item.value })),
      ),
    ),
  );

  stats.push(
    summarize(
      { key: "steps", label: "Steps", unit: "steps", color: "var(--hue-steps)", goodWhen: "up" },
      dailyLast(
        (input.steps?.items ?? []).map((item) => ({ date: item.date, value: item.steps })),
      ),
    ),
  );

  stats.push(
    summarize(
      { key: "sleep", label: "Sleep", unit: "h", color: "var(--hue-sleep)", goodWhen: "up" },
      dailyLast(
        (input.sleep?.items ?? [])
          .filter((item) => typeof item.totalSleepSeconds === "number")
          .map((item) => ({ date: dayKey(item.endAt), value: (item.totalSleepSeconds ?? 0) / 3600 })),
      ),
    ),
  );

  stats.push(
    summarize(
      { key: "sleep_score", label: "Sleep score", unit: "", color: "var(--hue-score)", goodWhen: "up" },
      dailyLast(
        (input.sleep?.items ?? [])
          .filter((item) => typeof item.sleepScore === "number")
          .map((item) => ({ date: dayKey(item.endAt), value: item.sleepScore ?? 0 })),
      ),
    ),
  );

  stats.push(
    summarize(
      { key: "hrv", label: "HRV", unit: "ms", color: "var(--hue-hrv)", goodWhen: "up" },
      dailyAverages(input.hrv),
    ),
  );

  stats.push(
    summarize(
      { key: "resting_hr", label: "Resting HR", unit: "bpm", color: "var(--hue-heart)", goodWhen: "down" },
      dailyLast(
        (input.activity?.items ?? [])
          .filter((item) => typeof item.minHeartRateBpm === "number")
          .map((item) => ({ date: item.date, value: item.minHeartRateBpm ?? 0 })),
      ),
    ),
  );

  stats.push(
    summarize(
      { key: "respiratory", label: "Respiratory", unit: "rpm", color: "var(--hue-resp)", goodWhen: null },
      dailyAverages(input.respiratory),
    ),
  );

  return stats.filter((stat) => stat.points.length > 0);
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
