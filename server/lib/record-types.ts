/**
 * Shared contract with the Android app's RecordTypes.kt: wire names must match
 * the Health Connect record class simple names exactly (e.g. "StepsRecord").
 */

export const RECORD_CATEGORIES = [
  "ACTIVITY",
  "BODY_MEASUREMENT",
  "CYCLE_TRACKING",
  "NUTRITION",
  "SLEEP",
  "VITALS",
] as const;

export type RecordCategory = (typeof RECORD_CATEGORIES)[number];

export interface RecordTypeDef {
  wireName: string;
  category: RecordCategory;
}

export const RECORD_TYPES: RecordTypeDef[] = [
  // Activity
  { wireName: "StepsRecord", category: "ACTIVITY" },
  { wireName: "DistanceRecord", category: "ACTIVITY" },
  { wireName: "ActiveCaloriesBurnedRecord", category: "ACTIVITY" },
  { wireName: "TotalCaloriesBurnedRecord", category: "ACTIVITY" },
  { wireName: "ExerciseSessionRecord", category: "ACTIVITY" },
  { wireName: "FloorsClimbedRecord", category: "ACTIVITY" },
  { wireName: "PowerRecord", category: "ACTIVITY" },
  { wireName: "SpeedRecord", category: "ACTIVITY" },
  { wireName: "Vo2MaxRecord", category: "ACTIVITY" },
  { wireName: "WheelchairPushesRecord", category: "ACTIVITY" },
  { wireName: "ElevationGainedRecord", category: "ACTIVITY" },
  { wireName: "CyclingPedalingCadenceRecord", category: "ACTIVITY" },
  // Body measurement
  { wireName: "HeightRecord", category: "BODY_MEASUREMENT" },
  { wireName: "WeightRecord", category: "BODY_MEASUREMENT" },
  { wireName: "BodyFatRecord", category: "BODY_MEASUREMENT" },
  { wireName: "BasalMetabolicRateRecord", category: "BODY_MEASUREMENT" },
  { wireName: "BodyWaterMassRecord", category: "BODY_MEASUREMENT" },
  { wireName: "BoneMassRecord", category: "BODY_MEASUREMENT" },
  { wireName: "LeanBodyMassRecord", category: "BODY_MEASUREMENT" },
  // Cycle tracking
  { wireName: "MenstruationFlowRecord", category: "CYCLE_TRACKING" },
  { wireName: "MenstruationPeriodRecord", category: "CYCLE_TRACKING" },
  { wireName: "OvulationTestRecord", category: "CYCLE_TRACKING" },
  { wireName: "CervicalMucusRecord", category: "CYCLE_TRACKING" },
  { wireName: "IntermenstrualBleedingRecord", category: "CYCLE_TRACKING" },
  { wireName: "SexualActivityRecord", category: "CYCLE_TRACKING" },
  { wireName: "BasalBodyTemperatureRecord", category: "CYCLE_TRACKING" },
  // Nutrition
  { wireName: "HydrationRecord", category: "NUTRITION" },
  { wireName: "NutritionRecord", category: "NUTRITION" },
  // Sleep
  { wireName: "SleepSessionRecord", category: "SLEEP" },
  // Vitals
  { wireName: "HeartRateRecord", category: "VITALS" },
  { wireName: "HeartRateVariabilityRmssdRecord", category: "VITALS" },
  { wireName: "BloodPressureRecord", category: "VITALS" },
  { wireName: "BloodGlucoseRecord", category: "VITALS" },
  { wireName: "BodyTemperatureRecord", category: "VITALS" },
  { wireName: "OxygenSaturationRecord", category: "VITALS" },
  { wireName: "RespiratoryRateRecord", category: "VITALS" },
  { wireName: "RestingHeartRateRecord", category: "VITALS" },
  { wireName: "SkinTemperatureRecord", category: "VITALS" },
];

export const RECORD_TYPE_WIRE_NAMES = RECORD_TYPES.map((t) => t.wireName) as [string, ...string[]];

export function categoryFor(wireName: string): RecordCategory | undefined {
  return RECORD_TYPES.find((t) => t.wireName === wireName)?.category;
}
