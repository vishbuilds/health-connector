/**
 * Allowlist + validation for records Claude is permitted to WRITE into Health Connect.
 *
 * SECURITY: this file is the single gate on writes. A record type that is not listed here
 * can never be written, no matter what the caller sends. Each entry declares:
 *   - `shape`: "instant" (a point-in-time record with a single `startTime`) or "interval"
 *     (a record spanning `startTime`..`endTime`). Interval writes MUST supply `endTime`.
 *   - `dataSchema`: a strict Zod schema for the type-specific `data` payload, with
 *     physiological bounds so absurd/dangerous values are rejected server-side before they
 *     ever reach the phone. The field names/units match the wire DTO produced on read
 *     (see android RecordDto.kt) so a value written here reads back identically.
 *
 * The Android app mirrors this allowlist in WritableRecordTypes.kt / RecordWriter.kt; keep
 * the two in sync when adding a type. Writes are a much smaller, deliberately curated subset
 * of the ~35 readable types — start conservative and extend as needed.
 */
import { z } from "zod";

export type WriteShape = "instant" | "interval";

export interface WritableTypeDef {
  wireName: string;
  shape: WriteShape;
  /** Schema for the `data` object; bounds are inclusive and chosen to be physiologically safe. */
  dataSchema: z.ZodType<Record<string, unknown>>;
  /** Human description surfaced to Claude via `list_writable_data_types`. */
  description: string;
}

// Common bounded number helper.
const bounded = (min: number, max: number) => z.number().finite().min(min).max(max);

export const WRITABLE_TYPES: WritableTypeDef[] = [
  {
    wireName: "WeightRecord",
    shape: "instant",
    description: "Body weight. data: { weightKg: number (0–1000) }",
    dataSchema: z.object({ weightKg: bounded(0, 1000) }).strict(),
  },
  {
    wireName: "HeightRecord",
    shape: "instant",
    description: "Body height. data: { heightMeters: number (0–3) }",
    dataSchema: z.object({ heightMeters: bounded(0, 3) }).strict(),
  },
  {
    wireName: "BodyFatRecord",
    shape: "instant",
    description: "Body fat percentage. data: { bodyFatPercentage: number (0–100) }",
    dataSchema: z.object({ bodyFatPercentage: bounded(0, 100) }).strict(),
  },
  {
    wireName: "BodyTemperatureRecord",
    shape: "instant",
    description: "Body temperature. data: { temperatureCelsius: number (20–45) }",
    dataSchema: z.object({ temperatureCelsius: bounded(20, 45) }).strict(),
  },
  {
    wireName: "BloodPressureRecord",
    shape: "instant",
    description:
      "Blood pressure. data: { systolicMmHg: number (20–300), diastolicMmHg: number (10–250) }",
    dataSchema: z
      .object({ systolicMmHg: bounded(20, 300), diastolicMmHg: bounded(10, 250) })
      .strict(),
  },
  {
    wireName: "BloodGlucoseRecord",
    shape: "instant",
    description: "Blood glucose. data: { levelMgPerDl: number (0–1000) }",
    dataSchema: z.object({ levelMgPerDl: bounded(0, 1000) }).strict(),
  },
  {
    wireName: "OxygenSaturationRecord",
    shape: "instant",
    description: "Blood oxygen saturation (SpO2). data: { oxygenSaturationPercentage: number (0–100) }",
    dataSchema: z.object({ oxygenSaturationPercentage: bounded(0, 100) }).strict(),
  },
  {
    wireName: "RestingHeartRateRecord",
    shape: "instant",
    description: "Resting heart rate. data: { restingHeartRateBpm: integer (0–300) }",
    dataSchema: z.object({ restingHeartRateBpm: z.number().int().min(0).max(300) }).strict(),
  },
  {
    wireName: "RespiratoryRateRecord",
    shape: "instant",
    description: "Respiratory rate. data: { respiratoryRateBreathsPerMinute: number (0–100) }",
    dataSchema: z.object({ respiratoryRateBreathsPerMinute: bounded(0, 100) }).strict(),
  },
  {
    wireName: "HydrationRecord",
    shape: "interval",
    description: "Water/fluid intake over a time span. data: { volumeLiters: number (0–10) }",
    dataSchema: z.object({ volumeLiters: bounded(0, 10) }).strict(),
  },
  {
    wireName: "StepsRecord",
    shape: "interval",
    description: "Step count over a time span. data: { count: integer (0–1000000) }",
    dataSchema: z.object({ count: z.number().int().min(0).max(1_000_000) }).strict(),
  },
  {
    wireName: "NutritionRecord",
    shape: "interval",
    description:
      "A logged food/meal over a time span. data: { name?: string, mealType?: integer (0–4), " +
      "energyKcal?: number (0–20000), proteinGrams?: number (0–2000), " +
      "totalCarbohydrateGrams?: number (0–2000), totalFatGrams?: number (0–2000) }",
    dataSchema: z
      .object({
        name: z.string().max(200).optional(),
        // Health Connect MealType constants: 0 UNKNOWN, 1 BREAKFAST, 2 LUNCH, 3 DINNER, 4 SNACK.
        mealType: z.number().int().min(0).max(4).optional(),
        energyKcal: bounded(0, 20000).optional(),
        proteinGrams: bounded(0, 2000).optional(),
        totalCarbohydrateGrams: bounded(0, 2000).optional(),
        totalFatGrams: bounded(0, 2000).optional(),
      })
      .strict(),
  },
];

export const WRITABLE_TYPES_BY_NAME = new Map(WRITABLE_TYPES.map((t) => [t.wireName, t]));

export const WRITABLE_TYPE_WIRE_NAMES = WRITABLE_TYPES.map((t) => t.wireName) as [
  string,
  ...string[],
];

/** Max clock skew we accept on write timestamps: reject anything more than this into the future. */
const MAX_FUTURE_SKEW_MS = 5 * 60 * 1000; // 5 minutes

const isoInstant = z.string().datetime({ offset: true });
const zoneOffset = z
  .string()
  .regex(/^[+-]\d{2}:\d{2}$/, "expected zone offset like +10:00")
  .nullable()
  .optional();

/**
 * Validates a full write request (type + time bounds + type-specific data). Returns a
 * discriminated-union-style result so the caller can produce a precise error for Claude.
 */
export function validateWrite(input: unknown):
  | { ok: true; value: ValidatedWrite }
  | { ok: false; error: string } {
  const envelope = z
    .object({
      type: z.enum(WRITABLE_TYPE_WIRE_NAMES),
      startTime: isoInstant,
      endTime: isoInstant.nullable().optional(),
      zoneOffset,
      data: z.record(z.string(), z.unknown()),
    })
    .strict()
    .safeParse(input);

  if (!envelope.success) {
    return { ok: false, error: envelope.error.issues.map((i) => i.message).join("; ") };
  }

  const def = WRITABLE_TYPES_BY_NAME.get(envelope.data.type)!;

  const start = Date.parse(envelope.data.startTime);
  if (Number.isNaN(start)) return { ok: false, error: "startTime is not a valid instant" };
  if (start > Date.now() + MAX_FUTURE_SKEW_MS) {
    return { ok: false, error: "startTime is too far in the future" };
  }

  let end: string | null = null;
  if (def.shape === "interval") {
    if (!envelope.data.endTime) {
      return { ok: false, error: `${def.wireName} is an interval record and requires endTime` };
    }
    const endMs = Date.parse(envelope.data.endTime);
    if (Number.isNaN(endMs)) return { ok: false, error: "endTime is not a valid instant" };
    if (endMs <= start) return { ok: false, error: "endTime must be after startTime" };
    if (endMs > Date.now() + MAX_FUTURE_SKEW_MS) {
      return { ok: false, error: "endTime is too far in the future" };
    }
    end = envelope.data.endTime;
  } else if (envelope.data.endTime) {
    return { ok: false, error: `${def.wireName} is an instantaneous record; do not pass endTime` };
  }

  const data = def.dataSchema.safeParse(envelope.data.data);
  if (!data.success) {
    return { ok: false, error: data.error.issues.map((i) => `data.${i.path.join(".")}: ${i.message}`).join("; ") };
  }

  return {
    ok: true,
    value: {
      type: envelope.data.type,
      startTime: envelope.data.startTime,
      endTime: end,
      zoneOffset: envelope.data.zoneOffset ?? null,
      data: data.data,
    },
  };
}

export interface ValidatedWrite {
  type: string;
  startTime: string;
  endTime: string | null;
  zoneOffset: string | null;
  data: Record<string, unknown>;
}
