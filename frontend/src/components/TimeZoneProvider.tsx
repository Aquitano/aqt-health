"use client";

import { createContext, use, type ReactNode } from "react";

const TimeZoneContext = createContext("UTC");

export function TimeZoneProvider({ timeZone, children }: { timeZone: string; children: ReactNode }) {
  return <TimeZoneContext value={timeZone}>{children}</TimeZoneContext>;
}

export function useTimeZone(): string {
  return use(TimeZoneContext);
}
