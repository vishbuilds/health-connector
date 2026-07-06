import { eq } from "drizzle-orm";
import { db, runReadOnlyQuery } from "@/db/client";
import { userProfile } from "@/db/schema";

export type UserSex = "male" | "female";
export type BmrFormula = "auto" | "katch_mcardle" | "mifflin_st_jeor";

export interface UserProfile {
  sex: UserSex;
  dateOfBirth: string;
  heightCm: number;
  bmrFormula: BmrFormula;
}

export interface BmrEstimate {
  kcalPerDay: number | null;
  source: "measured" | "katch_mcardle" | "mifflin_st_jeor" | "unavailable";
  formula: BmrFormula | "measured" | null;
  reason?: string;
}

export interface HealthProfileSnapshot {
  profile: UserProfile;
  asOfDate: string;
  age: number | null;
  latestWeightKg: number | null;
  latestBodyFatPct: number | null;
  measuredBmrKcalPerDay: number | null;
  bmr: BmrEstimate;
  basalCaloriesToday: number | null;
  basalDayFraction: number;
}

export const DEFAULT_USER_PROFILE: UserProfile = {
  sex: "male",
  dateOfBirth: "2000-06-28",
  heightCm: 165,
  bmrFormula: "auto",
};

export const USER_TIMEZONE = "Australia/Sydney";

export function ageOnDate(date: string, dateOfBirth: string): number | null {
  const [year, month, day] = date.split("-").map(Number);
  const [birthYear, birthMonth, birthDay] = dateOfBirth.split("-").map(Number);
  if (!year || !month || !day || !birthYear || !birthMonth || !birthDay) return null;
  let age = year - birthYear;
  if (month < birthMonth || (month === birthMonth && day < birthDay)) age -= 1;
  return age >= 0 ? age : null;
}

/** Basal burn accrues continuously across the whole local day. */
export function basalDayProgressFraction(isToday: boolean, nowMinutes: number): number {
  if (!isToday) return 1;
  return Math.max(0, Math.min(1, nowMinutes / (24 * 60)));
}

export function deriveBmrKcalPerDay(
  weightKg: number | null,
  bodyFatPct: number | null,
  targetDate: string,
  profile: UserProfile,
  measuredBmrKcalPerDay: number | null = null,
): BmrEstimate {
  if (measuredBmrKcalPerDay !== null && measuredBmrKcalPerDay > 0) {
    return { kcalPerDay: Math.round(measuredBmrKcalPerDay), source: "measured", formula: "measured" };
  }
  if (weightKg === null || weightKg <= 0) {
    return { kcalPerDay: null, source: "unavailable", formula: null, reason: "missing weight" };
  }

  if (
    (profile.bmrFormula === "auto" || profile.bmrFormula === "katch_mcardle") &&
    bodyFatPct !== null &&
    bodyFatPct > 0 &&
    bodyFatPct < 100
  ) {
    const leanMassKg = weightKg * (1 - bodyFatPct / 100);
    return {
      kcalPerDay: Math.round(370 + 21.6 * leanMassKg),
      source: "katch_mcardle",
      formula: "katch_mcardle",
    };
  }

  const age = ageOnDate(targetDate, profile.dateOfBirth);
  if (age === null) {
    return { kcalPerDay: null, source: "unavailable", formula: null, reason: "invalid date of birth" };
  }

  const sexConstant = profile.sex === "male" ? 5 : -161;
  return {
    kcalPerDay: Math.round(10 * weightKg + 6.25 * profile.heightCm - 5 * age + sexConstant),
    source: "mifflin_st_jeor",
    formula: "mifflin_st_jeor",
  };
}

export async function getUserProfile(): Promise<UserProfile> {
  let row: typeof userProfile.$inferSelect | undefined;
  try {
    [row] = await db.select().from(userProfile).where(eq(userProfile.id, "default")).limit(1);
  } catch (error) {
    console.warn("Falling back to default profile; user_profile query failed.", error);
    return { ...DEFAULT_USER_PROFILE };
  }
  if (!row) return { ...DEFAULT_USER_PROFILE };

  return {
    sex: row.sex === "female" ? "female" : "male",
    dateOfBirth: row.dateOfBirth,
    heightCm: Number(row.heightCm),
    bmrFormula:
      row.bmrFormula === "katch_mcardle" || row.bmrFormula === "mifflin_st_jeor" ? row.bmrFormula : "auto",
  };
}

export async function buildHealthProfileSnapshot(input: {
  targetDate: string;
  isToday: boolean;
  nowMinutes: number;
  latestWeightKg?: number | null;
  latestBodyFatPct?: number | null;
  measuredBmrKcalPerDay?: number | null;
}): Promise<HealthProfileSnapshot> {
  const profile = await getUserProfile();
  let latestWeightKg = input.latestWeightKg ?? null;
  let latestBodyFatPct = input.latestBodyFatPct ?? null;
  let measuredBmrKcalPerDay = input.measuredBmrKcalPerDay ?? null;

  if (
    input.latestWeightKg === undefined ||
    input.latestBodyFatPct === undefined ||
    input.measuredBmrKcalPerDay === undefined
  ) {
    const rows = await runReadOnlyQuery(`
      WITH params AS (SELECT DATE '${input.targetDate}' AS today),
      combined_records AS (
        SELECT hr.record_type, hr.start_time, hr.data
        FROM health_records hr
        WHERE hr.deleted_at IS NULL
          AND NOT EXISTS (
            SELECT 1 FROM pending_writes pw
            WHERE pw.status = 'pending'
              AND pw.health_connect_id IS NOT NULL
              AND pw.health_connect_id = hr.id
          )
        UNION ALL
        SELECT record_type, start_time, data
        FROM pending_writes pw
        WHERE (pw.status = 'pending' OR pw.status = 'applied')
          AND NOT EXISTS (
            SELECT 1 FROM health_records hr
            WHERE hr.deleted_at IS NULL
              AND pw.health_connect_id IS NOT NULL
              AND hr.id = pw.health_connect_id
              AND pw.status = 'applied'
          )
      )
      SELECT
        (SELECT CASE WHEN (data->>'weightKg') ~ '^-?[0-9]+([.][0-9]+)?$' THEN (data->>'weightKg')::numeric END
           FROM combined_records, params
           WHERE record_type = 'WeightRecord'
             AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date <= params.today
           ORDER BY start_time DESC LIMIT 1) AS latest_weight,
        (SELECT CASE WHEN (data->>'bodyFatPercentage') ~ '^-?[0-9]+([.][0-9]+)?$' THEN (data->>'bodyFatPercentage')::numeric END
           FROM combined_records, params
           WHERE record_type = 'BodyFatRecord'
             AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date <= params.today
           ORDER BY start_time DESC LIMIT 1) AS latest_body_fat,
        (SELECT CASE WHEN (data->>'basalMetabolicRateKcalPerDay') ~ '^-?[0-9]+([.][0-9]+)?$' THEN (data->>'basalMetabolicRateKcalPerDay')::numeric END
           FROM combined_records, params
           WHERE record_type = 'BasalMetabolicRateRecord'
             AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date <= params.today
           ORDER BY start_time DESC LIMIT 1) AS latest_bmr
    `);
    const row = rows[0] ?? {};
    if (input.latestWeightKg === undefined) latestWeightKg = num(row.latest_weight);
    if (input.latestBodyFatPct === undefined) latestBodyFatPct = num(row.latest_body_fat);
    if (input.measuredBmrKcalPerDay === undefined) measuredBmrKcalPerDay = num(row.latest_bmr);
  }

  const bmr = deriveBmrKcalPerDay(latestWeightKg, latestBodyFatPct, input.targetDate, profile, measuredBmrKcalPerDay);
  const basalDayFraction = basalDayProgressFraction(input.isToday, input.nowMinutes);
  const basalCaloriesToday = bmr.kcalPerDay === null ? null : Math.round(bmr.kcalPerDay * basalDayFraction);

  return {
    profile,
    asOfDate: input.targetDate,
    age: ageOnDate(input.targetDate, profile.dateOfBirth),
    latestWeightKg,
    latestBodyFatPct,
    measuredBmrKcalPerDay,
    bmr,
    basalCaloriesToday,
    basalDayFraction,
  };
}

function num(value: unknown): number | null {
  if (value === null || value === undefined) return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}
