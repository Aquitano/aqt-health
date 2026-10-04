import { useId } from "react";
import styles from "./PulseLine.module.css";

type PulseLineProps = {
  className?: string;
};

/**
 * EKG trace: a dim baseline with a bright comet segment sweeping across a
 * heartbeat waveform. Decorative; hidden from assistive tech and static when
 * the user prefers reduced motion. Used as the app's loading affordance.
 */
export function PulseLine({ className }: PulseLineProps) {
  const gradientId = useId();

  const waveform =
    "M0 20 H120 l6 -7 6 7 H190 l5 -16 6 26 5 -10 H320 l6 -5 6 5 H460 l5 -14 7 22 5 -8 H640";

  return (
    <svg
      aria-hidden="true"
      className={className}
      viewBox="0 0 640 40"
      fill="none"
      preserveAspectRatio="none"
    >
      <defs>
        <linearGradient id={gradientId} x1="0" y1="0" x2="1" y2="0">
          <stop offset="0" stopColor="var(--accent)" stopOpacity="0" />
          <stop offset="0.7" stopColor="var(--accent)" stopOpacity="0.9" />
          <stop offset="1" stopColor="var(--accent-hover)" stopOpacity="1" />
        </linearGradient>
      </defs>
      <path d={waveform} stroke="currentColor" strokeOpacity="0.14" strokeWidth="1.5" />
      <path
        className={styles.comet}
        d={waveform}
        pathLength={1}
        stroke={`url(#${gradientId})`}
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeDasharray="0.16 1"
        strokeDashoffset="1.16"
      />
    </svg>
  );
}
