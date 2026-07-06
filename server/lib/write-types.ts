/**
 * Allowlist of record types Claude is permitted to WRITE into Health Connect.
 *
 * SECURITY: this allowlist is the one deterministic write boundary that stays in code — a record
 * type not listed here can never be queued, no matter what the caller sends (enforced by the Zod
 * enum on the `write_records` tool's input schema). This is a *capability* gate, not a values
 * gate: it decides which kinds of data the connector may write at all.
 *
 * What is deliberately NOT enforced in code anymore: per-field physiological ranges, "endTime
 * required for interval types", and future-timestamp checks. Those are guidance, surfaced to the
 * model via each type's `description` (see the `health://writable-types` MCP resource) and the
 * write tool's own description. The phone's Health Connect insert is the real gate on malformed
 * writes: anything it rejects comes back as a `failed` row via /api/writes/ack, which is visible
 * to the model. Start permissive; add a rail here only if a real bad write actually happens.
 *
 * The Android app mirrors this allowlist in WritableRecordTypes.kt / RecordWriter.kt; keep the
 * two in sync when adding a type. Writes are a deliberately curated subset of the ~35 readable
 * types — start conservative and extend as needed.
 */
export type WriteShape = "instant" | "interval";

export interface WritableTypeDef {
  wireName: string;
  /** "instant" = point-in-time (startTime only); "interval" = spans startTime..endTime. */
  shape: WriteShape;
  /** Human guidance surfaced to Claude: fields, units, and sensible ranges. */
  description: string;
}

export const WRITABLE_TYPES: WritableTypeDef[] = [
  {
    wireName: "WeightRecord",
    shape: "instant",
    description: "Body weight. data: { weightKg: number } — typically 0–1000.",
  },
  {
    wireName: "HeightRecord",
    shape: "instant",
    description: "Body height. data: { heightMeters: number } — typically 0–3.",
  },
  {
    wireName: "BodyFatRecord",
    shape: "instant",
    description: "Body fat percentage. data: { bodyFatPercentage: number } — 0–100.",
  },
  {
    wireName: "BodyTemperatureRecord",
    shape: "instant",
    description: "Body temperature. data: { temperatureCelsius: number } — typically 20–45.",
  },
  {
    wireName: "BloodPressureRecord",
    shape: "instant",
    description:
      "Blood pressure. data: { systolicMmHg: number, diastolicMmHg: number } — systolic ~20–300, diastolic ~10–250.",
  },
  {
    wireName: "BloodGlucoseRecord",
    shape: "instant",
    description: "Blood glucose. data: { levelMgPerDl: number } — typically 0–1000.",
  },
  {
    wireName: "OxygenSaturationRecord",
    shape: "instant",
    description: "Blood oxygen saturation (SpO2). data: { oxygenSaturationPercentage: number } — 0–100.",
  },
  {
    wireName: "RestingHeartRateRecord",
    shape: "instant",
    description: "Resting heart rate. data: { restingHeartRateBpm: integer } — typically 0–300.",
  },
  {
    wireName: "RespiratoryRateRecord",
    shape: "instant",
    description: "Respiratory rate. data: { respiratoryRateBreathsPerMinute: number } — typically 0–100.",
  },
  {
    wireName: "HydrationRecord",
    shape: "interval",
    description: "Water/fluid intake over a time span. data: { volumeLiters: number } — typically 0–10. Requires endTime.",
  },
  {
    wireName: "StepsRecord",
    shape: "interval",
    description: "Step count over a time span. data: { count: integer } — 0 or more. Requires endTime.",
  },
  {
    wireName: "NutritionRecord",
    shape: "interval",
    description:
      "A logged food/meal over a time span. Requires endTime. data: { name?: string, " +
      "mealType?: integer (Health Connect MealType: 0 UNKNOWN, 1 BREAKFAST, 2 LUNCH, 3 DINNER, 4 SNACK), " +
      "energyKcal?: number, proteinGrams?: number, totalCarbohydrateGrams?: number, totalFatGrams?: number }.",
  },
];

export const WRITABLE_TYPE_WIRE_NAMES = WRITABLE_TYPES.map((t) => t.wireName) as [
  string,
  ...string[],
];
