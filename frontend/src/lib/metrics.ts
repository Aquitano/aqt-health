export const scalarMetricTypes = [
  "weight",
  "body_fat",
  "muscle",
  "water",
  "visceral_fat",
  "heart_rate",
  "hrv_rmssd",
  "respiratory_rate",
  "pulse_wave_velocity",
  "vascular_age",
  "standing_heart_rate",
  "fat_mass",
  "fat_free_mass",
  "bone_mass",
  "intracellular_water",
  "extracellular_water",
  "basal_metabolic_rate",
  "segmental_fat_mass",
  "segmental_muscle_mass",
  "segmental_fat_free_mass",
] as const;

type ScalarMetricType = (typeof scalarMetricTypes)[number];

export const scalarMetricLabels: Record<ScalarMetricType, string> = {
  weight: "Weight",
  body_fat: "Body fat",
  muscle: "Muscle",
  water: "Water",
  visceral_fat: "Visceral fat",
  heart_rate: "Heart rate",
  hrv_rmssd: "HRV (RMSSD)",
  respiratory_rate: "Respiratory rate",
  pulse_wave_velocity: "Pulse wave velocity",
  vascular_age: "Vascular age",
  standing_heart_rate: "Standing heart rate",
  fat_mass: "Fat mass",
  fat_free_mass: "Fat-free mass",
  bone_mass: "Bone mass",
  intracellular_water: "Intracellular water",
  extracellular_water: "Extracellular water",
  basal_metabolic_rate: "BMR",
  segmental_fat_mass: "Segmental fat",
  segmental_muscle_mass: "Segmental muscle",
  segmental_fat_free_mass: "Segmental fat-free",
};

export function scalarMetricLabel(metricType: string): string {
  const labels: Partial<Record<string, string>> = scalarMetricLabels;
  return labels[metricType] ?? metricType;
}
