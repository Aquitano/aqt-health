"use client";

import { useState } from "react";
import { ExpandedChartModal, type ChartSummary } from "./ExpandedChartModal";
import { HealthMetricChart } from "./charts/HealthMetricChart";
import { buildSummaries, type HealthCharts, type NormalizedChart } from "@/lib/healthCharts";
import styles from "./HealthDataVisualizations.module.css";

type HealthDataVisualizationsProps = {
  charts: HealthCharts;
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

export function HealthDataVisualizations({ charts, fromDate, toDate, timezone }: HealthDataVisualizationsProps) {
  const [modalChart, setModalChart] = useState<ModalChart | null>(null);
  const dateLabel = `${fromDate} to ${toDate} (${timezone})`;

  const primaryCharts: ChartCard[] = [
    {
      key: "body",
      title: "Body composition",
      description: "All body measurements available in the selected range.",
      chart: charts.body,
      height: 320,
    },
    {
      key: "weight",
      title: "Weight trend",
      description: "Weight measurements with latest, range, and movement detail.",
      chart: charts.weight,
      height: 320,
    },
  ];
  const secondaryCharts: ChartCard[] = [
    {
      key: "steps",
      title: "Daily steps",
      description: "Daily totals from normalized step summaries.",
      chart: charts.steps,
      height: 260,
    },
    {
      key: "activity",
      title: "Activity summaries",
      description: "Distance, energy, active minutes, and daily heart-rate ranges.",
      chart: charts.activity,
      height: 260,
    },
    {
      key: "heart",
      title: "Heart-rate detail",
      description: "Daily average, minimum, and maximum across the selected range.",
      chart: charts.heartRate,
      height: 260,
    },
    {
      key: "sleep",
      title: "Sleep sessions",
      description: "Sleep duration by recorded session.",
      chart: charts.sleep,
      height: 260,
    },
    {
      key: "sleep-summary",
      title: "Sleep summaries",
      description: "Sleep score, efficiency, and duration totals.",
      chart: charts.sleepSummary,
      height: 260,
    },
    {
      key: "respiratory",
      title: "Respiratory rate",
      description: "Breaths per minute across the selected range.",
      chart: charts.respiratoryRate,
      height: 260,
    },
    {
      key: "hrv",
      title: "HRV",
      description: "Heart-rate variability samples by metric type.",
      chart: charts.hrv,
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
