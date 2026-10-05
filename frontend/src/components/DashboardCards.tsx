import { Footprints, HeartPulse, Moon, Weight } from "lucide-react";
import { revealStyle } from "@/lib/styles";
import { AnimatedNumber } from "@/components/motion/AnimatedNumber";
import { formatDateTime, formatDuration, formatMeasurement, formatNumber } from "@/lib/format";
import { serverConfig } from "@/lib/serverConfig";
import type { DashboardSummaryResponse, DashboardTrendsResponse } from "@/lib/types";
import styles from "./DashboardCards.module.css";

type DashboardCardsProps = {
  summary?: DashboardSummaryResponse;
  trends?: DashboardTrendsResponse;
  rangeDays: number;
};

function TrendBadge({ percentChange, periodLabel }: { percentChange?: number | null; periodLabel?: string }) {
  if (percentChange == null) return null;
  const isPositive = percentChange > 0;
  const isNeutral = percentChange === 0;
  const sign = isNeutral ? "" : isPositive ? "+" : "";
  const arrow = isNeutral ? "→" : isPositive ? "↑" : "↓";
  return (
    <span
      className={styles.trend}
      data-direction={isNeutral ? "neutral" : isPositive ? "up" : "down"}
      title={`${sign}${percentChange.toFixed(1)}% ${periodLabel ?? `vs previous${percentChange === 0 ? " period" : ""}`}`}
    >
      {arrow} {sign}{Math.abs(percentChange).toFixed(1)}%{periodLabel ? ` ${periodLabel}` : null}
    </span>
  );
}

export function DashboardCards({ summary, trends, rangeDays }: DashboardCardsProps) {
  const periodLabel = trends && trends.periodDays !== rangeDays ? `vs prior ${trends.periodDays}d` : undefined;
  const cards = [
    {
      kind: "steps" as const,
      label: "Steps",
      value: formatNumber(summary?.steps.steps),
      detail: `${formatNumber(summary?.steps.sampleCount)} samples`,
      trend: trends?.steps?.percentChange,
      periodLabel,
      icon: Footprints,
    },
    {
      kind: "weight" as const,
      label: "Latest weight",
      value: formatMeasurement(summary?.latestWeight?.value, summary?.latestWeight?.unit),
      detail: summary?.latestWeight ? formatDateTime(summary.latestWeight.measuredAt, serverConfig.timeZone) : "No data",
      trend: trends?.weight?.percentChange,
      icon: Weight,
    },
    {
      kind: "heart" as const,
      label: "Heart rate",
      value: formatMeasurement(summary?.latestHeartRate?.value, summary?.latestHeartRate?.unit),
      detail: summary?.latestHeartRate?.context ?? "No data",
      trend: trends?.heartRate?.percentChange,
      periodLabel,
      icon: HeartPulse,
    },
    {
      kind: "sleep" as const,
      label: "Last sleep",
      value: formatDuration(summary?.lastSleepSession?.durationSeconds),
      detail: summary?.lastSleepSession ? formatDateTime(summary.lastSleepSession.startAt, serverConfig.timeZone) : "No data",
      trend: trends?.sleep?.percentChange,
      periodLabel,
      icon: Moon,
    },
  ];

  return (
    <section className={styles.cards} aria-label="Dashboard summary">
      {cards.map((card, index) => (
        <article
          className={styles.card}
          key={card.label}
          data-kind={card.kind}
          data-reveal
          style={revealStyle(index)}
        >
          <div className={styles.top}>
            <span className={styles.label}>{card.label}</span>
            <div className={styles.icon}>
              <card.icon size={18} aria-hidden="true" />
            </div>
          </div>
          <AnimatedNumber className={styles.value} value={card.value} />
          <span className={styles.detail}>
            {card.detail}
            <TrendBadge percentChange={card.trend} periodLabel={card.periodLabel} />
          </span>
        </article>
      ))}
    </section>
  );
}
