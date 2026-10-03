"use client";

import { useRouter, useSearchParams } from "next/navigation";
import type { FormEvent } from "react";
import { addUtcDays, defaultDateRange } from "@/lib/dates";
import { useTimeZone } from "./TimeZoneProvider";
import styles from "./DateRangeForm.module.css";

type DateRangeFormProps = {
  fromDate: string;
  toDate: string;
};

export function DateRangeForm({ fromDate, toDate }: DateRangeFormProps) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const timeZone = useTimeZone();

  function navigate(from: string, to: string) {
    const params = new URLSearchParams(searchParams.toString());
    params.delete("cursor");
    params.set("fromDate", from);
    params.set("toDate", to);
    router.push(`?${params.toString()}`);
  }

  function applyPreset(days: number) {
    const to = defaultDateRange(timeZone).toDate;
    navigate(addUtcDays(to, -(days - 1)), to);
  }

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const from = form.get("fromDate");
    const to = form.get("toDate");
    navigate(typeof from === "string" ? from : fromDate, typeof to === "string" ? to : toDate);
  }

  return (
    <form key={`${fromDate}:${toDate}`} className={styles.form} onSubmit={onSubmit}>
      <div className={styles.field}>
        <span className={styles.fieldLabel}>From</span>
        <input className={styles.input} name="fromDate" type="date" defaultValue={fromDate} />
      </div>
      <div className={styles.field}>
        <span className={styles.fieldLabel}>To</span>
        <input className={styles.input} name="toDate" type="date" defaultValue={toDate} />
      </div>
      <button className={styles.submit} type="submit">Apply</button>
      <div className={styles.presets}>
        <button className={styles.preset} type="button" onClick={() => applyPreset(1)}>Today</button>
        <button className={styles.preset} type="button" onClick={() => applyPreset(7)}>7d</button>
        <button className={styles.preset} type="button" onClick={() => applyPreset(30)}>30d</button>
        <button className={styles.preset} type="button" onClick={() => applyPreset(90)}>90d</button>
      </div>
    </form>
  );
}
