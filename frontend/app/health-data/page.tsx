import Link from "next/link";
import { DashboardCards } from "@/components/DashboardCards";
import { DateRangeForm } from "@/components/DateRangeForm";
import { DayOverview } from "@/components/DayOverview";
import { ErrorNotice } from "@/components/ErrorNotice";
import { HealthDataVisualizations } from "@/components/HealthDataVisualizations";
import { LoadingPulse } from "@/components/motion/LoadingPulse";
import { MetricHighlights } from "@/components/MetricHighlights";
import { PageHeader } from "@/components/PageHeader";
import { StatusBar } from "@/components/StatusBar";
import { getHealthDataPageSources, type HealthDataPageSources } from "@/lib/aqtHealthApi";
import { addUtcDays, parseDateRange, rangeDays, startOfDayInstant } from "@/lib/dates";
import { buildHealthCharts } from "@/lib/healthCharts";
import { serverConfig } from "@/lib/serverConfig";
import type { ScalarSample } from "@/lib/types";
import { Suspense } from "react";

type PageProps = {
  searchParams?: Promise<Record<string, string | string[] | undefined>>;
};

export default async function HealthDataPage({ searchParams }: PageProps) {
  const params = (await searchParams) ?? {};
  const range = parseDateRange(params, serverConfig.timeZone);

  const sources = getHealthDataPageSources(range.fromDate, range.toDate, serverConfig.timeZone);

  return (
    <>
      <PageHeader
        eyebrow="Local health hub"
        title="Health data"
        description="Daily overview and recent normalized metrics across connected providers."
        actions={
          <Suspense>
            <DateRangeForm fromDate={range.fromDate} toDate={range.toDate} />
          </Suspense>
        }
      />

      {range.warning ? <div className="notice warning">{range.warning}</div> : null}

      <Suspense fallback={<LoadingPulse />}>
        <OverviewSection sources={sources} fromDate={range.fromDate} toDate={range.toDate} />
      </Suspense>

      <Suspense fallback={<LoadingPulse label="Loading visual analytics…" />}>
        <VisualizationsSection sources={sources} fromDate={range.fromDate} toDate={range.toDate} />
      </Suspense>

      <p><Link href={`/health-data/raw?${new URLSearchParams({ fromDate: range.fromDate, toDate: range.toDate })}`} prefetch={false}>Browse raw data</Link></p>
    </>
  );
}

type SectionProps = {
  sources: HealthDataPageSources;
  fromDate: string;
  toDate: string;
};

async function OverviewSection({ sources, fromDate, toDate }: SectionProps) {
  const [
    health,
    summary,
    trends,
    healthDay,
    bodyMeasurements,
    latestActivity,
    latestSleepSummary,
    latestRespiratoryRate,
    latestHrv,
    latestBloodPressure,
  ] = await Promise.all([
    sources.health,
    sources.summary,
    sources.trends,
    sources.healthDay,
    sources.bodyMeasurements,
    sources.latestActivity,
    sources.latestSleepSummary,
    sources.latestRespiratoryRate,
    sources.latestHrv,
    sources.latestBloodPressure,
  ]);

  const bodyMeasurementItems = bodyMeasurements.ok ? bodyMeasurements.data.items : [];
  const weightFrom = Date.parse(startOfDayInstant(addUtcDays(toDate, -6), serverConfig.timeZone));
  const weightTrendItems = bodyMeasurementItems.filter((item) => isWeightTrendItem(item, weightFrom));
  const weightDelta = weightChange(weightTrendItems);

  return (
    <>
      <StatusBar health={health} fromDate={fromDate} toDate={toDate} />
      <ErrorNotice result={health} />
      <ErrorNotice result={summary} />
      <ErrorNotice result={trends} />
      <ErrorNotice result={healthDay} />
      <ErrorNotice result={bodyMeasurements} />

      <DashboardCards
        summary={summary.ok ? summary.data : undefined}
        trends={trends.ok ? trends.data : undefined}
        rangeDays={rangeDays(fromDate, toDate)}
      />
      <DayOverview
        day={healthDay.ok ? healthDay.data : undefined}
        weightDelta7d={weightDelta?.value}
        weightDelta7dUnit={weightDelta?.unit}
      />

      <MetricHighlights
        latestActivity={latestActivity.ok ? latestActivity.data : undefined}
        latestSleepSummary={latestSleepSummary.ok ? latestSleepSummary.data : undefined}
        latestRespiratoryRate={latestRespiratoryRate.ok ? latestRespiratoryRate.data : undefined}
        latestHrv={latestHrv.ok ? latestHrv.data : undefined}
        latestBloodPressure={latestBloodPressure?.ok ? latestBloodPressure.data : undefined}
      />
    </>
  );
}

async function VisualizationsSection({ sources, fromDate, toDate }: SectionProps) {
  const [
    activitySummaries,
    bodyMeasurements,
    dailySteps,
    heartRateDaily,
    hrvSamples,
    sleepNights,
    respiratoryRates,
    sleepSummaries,
  ] = await Promise.all([
    sources.activitySummaries,
    sources.bodyMeasurements,
    sources.dailySteps,
    sources.heartRateDaily,
    sources.hrvSamples,
    sources.sleepNights,
    sources.respiratoryRates,
    sources.sleepSummaries,
  ]);

  const responses = [
    activitySummaries, bodyMeasurements, dailySteps, heartRateDaily, hrvSamples,
    sleepNights, respiratoryRates, sleepSummaries,
  ];
  const limited = responses.some((response) => response.ok && Boolean(response.data.meta.nextCursor));

  return (
    <>
      {responses.map((response, index) =>
        response.ok ? null : <ErrorNotice key={index} result={response} />,
      )}
      {limited ? (
        <div className="notice warning">
          Some charts show a limited set of samples. Use Trends for complete daily aggregates,
          or narrow the date range.
        </div>
      ) : null}
      <HealthDataVisualizations
        charts={buildHealthCharts(
          {
            activitySummaries: activitySummaries.ok ? activitySummaries.data : undefined,
            bodyMeasurements: bodyMeasurements.ok ? bodyMeasurements.data : undefined,
            dailySteps: dailySteps.ok ? dailySteps.data : undefined,
            heartRateDaily: heartRateDaily.ok ? heartRateDaily.data : undefined,
            hrvSamples: hrvSamples.ok ? hrvSamples.data : undefined,
            sleepNights: sleepNights.ok ? sleepNights.data : undefined,
            respiratoryRates: respiratoryRates.ok ? respiratoryRates.data : undefined,
            sleepSummaries: sleepSummaries.ok ? sleepSummaries.data : undefined,
          },
          serverConfig.timeZone,
        )}
        fromDate={fromDate}
        toDate={toDate}
      />
    </>
  );
}

function isWeightTrendItem(item: ScalarSample, from: number): boolean {
  const measuredAt = Date.parse(item.measuredAt);
  return item.metricType === "weight" && !Number.isNaN(measuredAt) && measuredAt >= from;
}

function weightChange(items: ScalarSample[]): { value: number; unit: string } | null {
  const sorted = [...items].sort((a, b) => b.measuredAt.localeCompare(a.measuredAt) || b.id - a.id);
  const latest = sorted[0];
  const oldest = sorted[sorted.length - 1];
  if (!latest || !oldest || latest.id === oldest.id || latest.unit !== oldest.unit) {
    return null;
  }

  return {
    value: latest.value - oldest.value,
    unit: latest.unit,
  };
}
