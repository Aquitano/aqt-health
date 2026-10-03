import Link from "next/link";
import formStyles from "@/components/DateRangeForm.module.css";
import type { ComponentType } from "react";
import { Suspense } from "react";
import { PageHeader } from "@/components/PageHeader";
import { DateRangeForm } from "@/components/DateRangeForm";
import { DataSection } from "@/components/DataSection";
import { ActivitySummariesTable } from "@/components/tables/ActivitySummariesTable";
import { BloodPressureTable } from "@/components/tables/BloodPressureTable";
import { BodyMeasurementsTable } from "@/components/tables/BodyMeasurementsTable";
import { CardiovascularTable } from "@/components/tables/CardiovascularTable";
import { DailyStepsTable } from "@/components/tables/DailyStepsTable";
import { ExtendedBodyMeasurementsTable } from "@/components/tables/ExtendedBodyMeasurementsTable";
import { HeartRateTable } from "@/components/tables/HeartRateTable";
import { HrvTable } from "@/components/tables/HrvTable";
import { RespiratoryRateTable } from "@/components/tables/RespiratoryRateTable";
import { SleepSessionsTable } from "@/components/tables/SleepSessionsTable";
import { SleepSummariesTable } from "@/components/tables/SleepSummariesTable";
import { aqtHealthClient as client } from "@/lib/aqtHealthClient";
import {
  addUtcDays,
  first,
  parseDateRange,
  startOfDayInstant,
} from "@/lib/dates";
import { serverConfig } from "@/lib/serverConfig";
import type { ApiResult, ScalarSample } from "@/lib/types";

const scalarTables = {
  weight: BodyMeasurementsTable,
  body_fat: BodyMeasurementsTable,
  muscle: BodyMeasurementsTable,
  water: BodyMeasurementsTable,
  visceral_fat: BodyMeasurementsTable,
  heart_rate: HeartRateTable,
  hrv_rmssd: HrvTable,
  respiratory_rate: RespiratoryRateTable,
  pulse_wave_velocity: CardiovascularTable,
  vascular_age: CardiovascularTable,
  standing_heart_rate: CardiovascularTable,
  fat_mass: ExtendedBodyMeasurementsTable,
  fat_free_mass: ExtendedBodyMeasurementsTable,
  bone_mass: ExtendedBodyMeasurementsTable,
  intracellular_water: ExtendedBodyMeasurementsTable,
  extracellular_water: ExtendedBodyMeasurementsTable,
  basal_metabolic_rate: ExtendedBodyMeasurementsTable,
  segmental_fat_mass: ExtendedBodyMeasurementsTable,
  segmental_muscle_mass: ExtendedBodyMeasurementsTable,
  segmental_fat_free_mass: ExtendedBodyMeasurementsTable,
} satisfies Record<string, ComponentType<{ items: ScalarSample[] }>>;
const datasets = [
  "steps",
  "activity",
  "sleep-sessions",
  "sleep-summaries",
  "blood-pressure",
  ...(Object.keys(scalarTables) as (keyof typeof scalarTables)[]),
] as const;
type Dataset = (typeof datasets)[number];

function isDataset(value: string): value is Dataset {
  return (datasets as readonly string[]).includes(value);
}

export default async function RawDataPage({
  searchParams,
}: {
  searchParams?: Promise<Record<string, string | string[] | undefined>>;
}) {
  const params = (await searchParams) ?? {};
  const timezone = serverConfig.timeZone;
  const range = parseDateRange(params, timezone);
  const requestedDataset = first(params.dataset) ?? "steps";
  const dataset: Dataset = isDataset(requestedDataset)
    ? requestedDataset
    : "steps";
  const cursor = first(params.cursor);
  const query = new URLSearchParams({
    fromDate: range.fromDate,
    toDate: range.toDate,
    dataset,
  });
  const instantQuery = {
    from: startOfDayInstant(range.fromDate, timezone),
    to: startOfDayInstant(addUtcDays(range.toDate, 1), timezone),
    limit: 100,
    includeSource: true,
    order: "desc" as const,
    cursor,
  };
  const dateQuery = {
    fromDate: range.fromDate,
    toDate: range.toDate,
    limit: 100,
    includeSource: true,
    order: "desc" as const,
    cursor,
  };

  async function table<T>(
    response: Promise<
      ApiResult<{ items: T[]; meta: { nextCursor?: string | null } }>
    >,
    Table: ComponentType<{ items: T[] }>
  ) {
    const result = await response;
    const nextCursor = result.ok ? result.data.meta.nextCursor : null;
    const next = new URLSearchParams(query);
    if (nextCursor) next.set("cursor", nextCursor);
    return (
      <>
        <DataSection title={dataset.replaceAll(/[_-]/g, " ")} result={result}>
          {(data) => <Table items={data.items} />}
        </DataSection>
        {cursor || nextCursor ? (
          <nav aria-label="Raw data pages">
            {cursor ? (
              <Link href={`?${query}`} prefetch={false}>
                First page
              </Link>
            ) : null}
            {nextCursor ? (
              <>
                {" "}
                <Link href={`?${next}`} prefetch={false}>
                  Next 100 records
                </Link>
              </>
            ) : null}
          </nav>
        ) : null}
      </>
    );
  }

  function loadTable() {
    switch (dataset) {
      case "steps":
        return table(
          client.listDailyStepSummaries({ ...dateQuery, sort: "date" }),
          DailyStepsTable
        );
      case "activity":
        return table(
          client.listActivitySummaries({ ...dateQuery, sort: "date" }),
          ActivitySummariesTable
        );
      case "sleep-sessions":
        return table(
          client.listSleepSessions({ ...instantQuery, sort: "startAt" }),
          SleepSessionsTable
        );
      case "sleep-summaries":
        return table(
          client.listSleepSummaries({ ...instantQuery, sort: "endAt" }),
          SleepSummariesTable
        );
      case "blood-pressure":
        return table(
          client.listBloodPressure({ ...instantQuery, sort: "measuredAt" }),
          BloodPressureTable
        );
      default:
        return table(
          client.listScalarSamples(dataset, {
            ...instantQuery,
            sort: "measuredAt",
            raw: true,
          }),
          scalarTables[dataset]
        );
    }
  }

  return (
    <>
      <PageHeader
        title="Raw health data"
        description="Browse source records, 100 at a time."
        actions={
          <Suspense>
            <DateRangeForm fromDate={range.fromDate} toDate={range.toDate} />
          </Suspense>
        }
      />
      {range.warning ? (
        <div className="notice warning">{range.warning}</div>
      ) : null}
      <p>
        <Link href={`/health-data?${query}`} prefetch={false}>
          Back to health data
        </Link>
      </p>
      <form className={formStyles.form}>
        <input type="hidden" name="fromDate" value={range.fromDate} />
        <input type="hidden" name="toDate" value={range.toDate} />
        <label className={formStyles.field}>
          <span className={formStyles.fieldLabel}>Data</span>
          <select
            className={formStyles.input}
            name="dataset"
            defaultValue={dataset}
          >
            {datasets.map((name) => (
              <option key={name} value={name}>
                {name.replaceAll(/[_-]/g, " ")}
              </option>
            ))}
          </select>
        </label>{" "}
        <button className={formStyles.submit} type="submit">
          Show records
        </button>
      </form>
      {dataset === "sleep-sessions" || dataset === "sleep-summaries" ? (
        <p>
          Sleep records are filtered by their start time in {timezone}.
          Overnight records appear on the day they began.
        </p>
      ) : null}
      <Suspense fallback={<p>Loading records...</p>}>{loadTable()}</Suspense>
    </>
  );
}
