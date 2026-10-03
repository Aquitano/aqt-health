export type DateRange = {
  fromDate: string;
  toDate: string;
  warning?: string;
};

const dateOnlyPattern = /^\d{4}-\d{2}-\d{2}$/;
const maxRangeDays = 366;

export function defaultDateRange(timezone: string, now = new Date()): DateRange {
  const toDate = dateInTimeZone(now, timezone);

  return {
    fromDate: addUtcDays(toDate, -6),
    toDate,
  };
}

export function parseDateRange(
  values: {
    fromDate?: string | string[];
    toDate?: string | string[];
  },
  timezone: string,
): DateRange {
  // Resolve the default range in the app timezone so an unset range matches local dates
  // rather than UTC "today" (off-by-one east/west of UTC).
  const fallback = defaultDateRange(timezone);
  const fromDate = first(values.fromDate) ?? fallback.fromDate;
  const toDate = first(values.toDate) ?? fallback.toDate;

  if (!isDateOnly(fromDate) || !isDateOnly(toDate)) {
    return {
      ...fallback,
      warning: "Invalid date query parameters were ignored.",
    };
  }

  if (fromDate > toDate) {
    return {
      ...fallback,
      warning: "The selected range was invalid and was reset to the default week.",
    };
  }

  if (rangeDays(fromDate, toDate) > maxRangeDays) {
    return {
      fromDate: addUtcDays(toDate, -(maxRangeDays - 1)),
      toDate,
      warning: `Ranges are limited to ${maxRangeDays} days, so this shows the ${maxRangeDays} days ending ${toDate}.`,
    };
  }

  return { fromDate, toDate };
}

/** Number of calendar days in an inclusive date-only range. */
export function rangeDays(fromDate: string, toDate: string): number {
  return (Date.parse(toDate) - Date.parse(fromDate)) / 86_400_000 + 1;
}

function dateOnlyToUtcInstant(date: string): string {
  return `${date}T00:00:00.000Z`;
}

export function addUtcDays(date: string, days: number): string {
  const parsed = Date.parse(`${date}T00:00:00.000Z`);
  if (Number.isNaN(parsed)) return date;
  const next = new Date(parsed);
  next.setUTCDate(next.getUTCDate() + days);
  return toDateInputValue(next);
}

export function first(value?: string | string[]): string | undefined {
  if (Array.isArray(value)) return value[0];
  return value;
}

export function isDateOnly(value: string): boolean {
  if (!dateOnlyPattern.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}

function toDateInputValue(value: Date): string {
  return value.toISOString().slice(0, 10);
}

const dayFormatters = new Map<string, Intl.DateTimeFormat>();

// "en-CA" formats as YYYY-MM-DD, and the timeZone option yields the calendar date in that zone.
function dayFormatter(timeZone: string): Intl.DateTimeFormat {
  const cached = dayFormatters.get(timeZone);
  if (cached) return cached;
  const formatter = new Intl.DateTimeFormat("en-CA", {
    timeZone, year: "numeric", month: "2-digit", day: "2-digit",
  });
  dayFormatters.set(timeZone, formatter);
  return formatter;
}

export function dateInTimeZone(value: Date | number, timeZone: string): string {
  return dayFormatter(timeZone).format(value);
}

/** First instant of the calendar date, including days with a midnight DST change. */
export function startOfDayInstant(date: string, timezone: string): string {
  if (timezone === "UTC") return dateOnlyToUtcInstant(date);
  const formatter = dayFormatter(timezone);
  const center = Date.parse(dateOnlyToUtcInstant(date));
  let low = center - 36 * 3_600_000;
  let high = center + 36 * 3_600_000;
  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if (formatter.format(middle) < date) low = middle + 1;
    else high = middle;
  }
  return new Date(low).toISOString();
}
