"use client";

import { useEffect, useRef, type RefObject } from "react";

type AnimatedNumberProps = {
  value: string;
  className?: string;
  duration?: number;
};

const NUMERIC_TOKEN = /\d[\d,]*(?:\.\d+)?/g;

/**
 * Renders a formatted value (e.g. "7,532", "72.5 kg") server-side, then counts
 * the numeric tokens up from zero on mount. Non-numeric values render as-is.
 */
export function AnimatedNumber({ value, className, duration = 0.9 }: AnimatedNumberProps) {
  const ref = useRef<HTMLSpanElement>(null);
  useCountUp(ref, value, duration);

  return (
    <span ref={ref} className={className}>
      {value}
    </span>
  );
}

function useCountUp(ref: RefObject<HTMLElement | null>, value: string, durationSeconds: number) {
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    if (!/\d/.test(value)) return;

    const start = performance.now();
    let frame = requestAnimationFrame(function tick(now) {
      const elapsed = Math.max(0, Math.min((now - start) / (durationSeconds * 1000), 1));
      el.textContent = scaleNumbers(value, 1 - (1 - elapsed) ** 3);
      if (elapsed < 1) frame = requestAnimationFrame(tick);
    });

    return () => {
      cancelAnimationFrame(frame);
      el.textContent = value;
    };
  }, [ref, value, durationSeconds]);
}

function scaleNumbers(value: string, progress: number): string {
  return value.replace(NUMERIC_TOKEN, (match) => {
    const target = Number(match.replace(/,/g, ""));
    if (Number.isNaN(target)) return match;
    const decimals = match.split(".")[1]?.length ?? 0;
    const current = target * progress;
    if (!match.includes(",")) return current.toFixed(decimals);
    return current.toLocaleString("en", {
      minimumFractionDigits: decimals,
      maximumFractionDigits: decimals,
    });
  });
}
