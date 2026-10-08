import Link from "next/link";
import styles from "./TrendsRangeTabs.module.css";

type TrendsRangeTabsProps = {
  days: number;
  options: readonly number[];
};

export function TrendsRangeTabs({ days, options }: TrendsRangeTabsProps) {
  return (
    <nav className={styles.tabs} aria-label="Trend window">
      {options.map((option) => (
        <Link
          key={option}
          href={`?days=${option}`}
          prefetch={false}
          className={option === days ? styles.tabActive : styles.tab}
          aria-current={option === days ? "page" : undefined}
        >
          {option}d
        </Link>
      ))}
    </nav>
  );
}
