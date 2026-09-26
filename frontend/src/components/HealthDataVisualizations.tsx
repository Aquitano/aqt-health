"use client";

import { useMemo, useState } from "react";
import { ExpandedChartModal, type ChartSummary } from "./ExpandedChartModal";
import { HealthMetricChart } from "./charts/HealthMetricChart";
import type {
  ActivitySummariesResponse,
  HeartRateDailyPoint,
  ScalarSamplesResponse,
  SleepNightsResponse,
  SleepSummariesResponse,
  StepDailySummariesResponse,
} from "@/lib/types";
import {
  buildBodyChart,
  buildWeightChart,
  buildStepsChart,
  buildActivityChart,
  buildHeartRateDailyChart,
  buildSleepChart,
  buildSleepSummaryChart,
  buildRespiratoryRateChart,
  buildHrvChart,
  buildSummaries,
  type NormalizedChart,
} from "@/lib/healthCharts";
import styles from "./HealthDataVisualizations.module.css";

type HealthDataVisualizationsProps = {
  activitySummaries?: ActivitySummariesResponse;
  bodyMeasurements?: ScalarSamplesResponse;
  dailySteps?: StepDailySummariesResponse;
  heartRateDaily: HeartRateDailyPoint[];
  hrvSamples?: ScalarSamplesResponse;
  sleepNights?: SleepNightsResponse;
  respiratoryRates?: ScalarSamplesResponse;
  sleepSummaries?: SleepSummariesResponse;
  fromDate: string;
  toDate: string;
  timezone: string;
};

type ModalChart = NormalizedChart & {
  title: string;
  description?: string;
  summaries: ChartSummary[];
};

type ChartCard = {
  key: string;
  title: string;
  description: string;
  chart: NormalizedChart;
  height: number;
};

export function HealthDataVisualizations({
  activitySummaries,
  bodyMeasurements,
  dailySteps,
  heartRateDaily,
  hrvSamples,
  sleepNights,
  respiratoryRates,
  sleepSummaries,
  fromDate,
  toDate,
  timezone,
}: HealthDataVisualizationsProps) {
  const [modalChart, setModalChart] = useState<ModalChart | null>(null);
  const bodyChart = useMemo(() => buildBodyChart(bodyMeasurements?.items ?? []), [bodyMeasurements]);
  const weightChart = useMemo(() => buildWeightChart(bodyChart), [bodyChart]);
  const stepsChart = useMemo(() => buildStepsChart(dailySteps?.items ?? []), [dailySteps]);
  const activityChart = useMemo(() => buildActivityChart(activitySummaries?.items ?? []), [activitySummaries]);
  const heartRateChart = useMemo(() => buildHeartRateDailyChart(heartRateDaily), [heartRateDaily]);
  const sleepChart = useMemo(() => buildSleepChart(sleepNights), [sleepNights]);
  const sleepSummaryChart = useMemo(() => buildSleepSummaryChart(sleepSummaries?.items ?? []), [sleepSummaries]);
  const respiratoryRateChart = useMemo(
    () => buildRespiratoryRateChart(respiratoryRates?.items ?? []),
    [respiratoryRates],
  );
  const hrvChart = useMemo(() => buildHrvChart(hrvSamples?.items ?? []), [hrvSamples]);
  const dateLabel = `${fromDate} to ${toDate} (${timezone})`;

  // The key remounts HealthMetricChart when the chart's shape changes so its
  // internal visible-series state resets.
  const primaryCharts: ChartCard[] = [
    {
      key: `body-${bodyChart.defaultVisibleMetricKeys.join("-")}-${bodyChart.data.length}`,
      title: "Body composition",
      description: "All body measurements available in the selected range.",
      chart: bodyChart,
      height: 320,
    },
    {
      key: `weight-${weightChart.data.length}`,
      title: "Weight trend",
      description: "Weight measurements with latest, range, and movement detail.",
      chart: weightChart,
      height: 320,
    },
  ];
  const secondaryCharts: ChartCard[] = [
    {
      key: `steps-${stepsChart.data.length}`,
      title: "Daily steps",
      description: "Daily totals from normalized step summaries.",
      chart: stepsChart,
      height: 260,
    },
    {
      key: `activity-${activityChart.data.length}`,
      title: "Activity summaries",
      description: "Distance, energy, active minutes, and daily heart-rate ranges.",
      chart: activityChart,
      height: 260,
    },
    {
      key: `heart-${heartRateChart.data.length}`,
      title: "Heart-rate detail",
      description: "Daily average, minimum, and maximum across the selected range.",
      chart: heartRateChart,
      height: 260,
    },
    {
      key: `sleep-${sleepChart.data.length}`,
      title: "Sleep sessions",
      description: "Sleep duration by recorded session.",
      chart: sleepChart,
      height: 260,
    },
    {
      key: `sleep-summary-${sleepSummaryChart.data.length}`,
      title: "Sleep summaries",
      description: "Sleep score, efficiency, and duration totals.",
      chart: sleepSummaryChart,
      height: 260,
    },
    {
      key: `respiratory-${respiratoryRateChart.data.length}`,
      title: "Respiratory rate",
      description: "Breaths per minute across the selected range.",
      chart: respiratoryRateChart,
      height: 260,
    },
    {
      key: `hrv-${hrvChart.data.length}`,
      title: "HRV",
      description: "Heart-rate variability samples by metric type.",
      chart: hrvChart,
      height: 260,
    },
  ];

  const renderChart = ({ key, title, description, chart, height }: ChartCard) => (
    <HealthMetricChart
      key={key}
      title={title}
      description={description}
      series={chart.series}
      data={chart.data}
      defaultVisibleMetricKeys={chart.defaultVisibleMetricKeys}
      height={height}
      onExpand={(visible) =>
        openModal({
          title,
          description: dateLabel,
          chart,
          visibleMetricKeys: visible,
        })
      }
    />
  );

  return (
    <section className={styles.section} aria-label="Health visualizations" data-reveal>
      <div className={styles.heading}>
        <div>
          <h2>Visual analytics</h2>
          <p>{dateLabel}</p>
        </div>
      </div>

      <div className={styles.primaryGrid}>{primaryCharts.map(renderChart)}</div>

      <div className={styles.secondaryGrid}>{secondaryCharts.map(renderChart)}</div>

      {modalChart ? (
        <ExpandedChartModal
          title={modalChart.title}
          description={modalChart.description}
          series={modalChart.series}
          data={modalChart.data}
          defaultVisibleMetricKeys={modalChart.defaultVisibleMetricKeys}
          summaries={modalChart.summaries}
          details={modalChart.details}
          onClose={() => setModalChart(null)}
        />
      ) : null}
    </section>
  );

  function openModal({
    title,
    description,
    chart,
    visibleMetricKeys,
  }: {
    title: string;
    description: string;
    chart: NormalizedChart;
    visibleMetricKeys: string[];
  }) {
    const selectedKeys = visibleMetricKeys.length ? visibleMetricKeys : chart.defaultVisibleMetricKeys;
    setModalChart({
      title,
      description,
      series: chart.series,
      data: chart.data,
      defaultVisibleMetricKeys: selectedKeys,
      summaries: buildSummaries(chart.details, selectedKeys),
      details: chart.details.filter((detail) => selectedKeys.includes(detail.metricKey)),
    });
  }
}
