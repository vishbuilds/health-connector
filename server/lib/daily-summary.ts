import { and, eq, gte, isNull, lt, sql } from "drizzle-orm";
import { DateTime } from "luxon";
import { db } from "@/db/client";
import { healthRecords } from "@/db/schema";

/**
 * The user is Australia-based; "date" in get_daily_summary is interpreted as a
 * calendar day in this timezone (correctly handles the AEST/AEDT transition).
 */
const USER_TIMEZONE = "Australia/Sydney";

/**
 * Field-name contract for the jsonb `data` column, matching the Android app's
 * actual wire format (network/dto/RecordDto.kt::toUpsertJson). Keys always
 * carry an explicit unit suffix rather than relying on Health Connect's unit
 * objects. Durations for interval records (sleep, exercise) are NOT in `data`
 * — they're derived from the row's own start_time/end_time columns.
 *
 *   StepsRecord                 { count: number }
 *   DistanceRecord               { distanceMeters: number }
 *   ActiveCaloriesBurnedRecord    { energyKcal: number }
 *   TotalCaloriesBurnedRecord     { energyKcal: number }
 *   FloorsClimbedRecord           { floors: number }
 *   HydrationRecord               { volumeLiters: number }
 *   SleepSessionRecord            { title?, notes?, stages: [...] } (duration = end_time - start_time)
 *   ExerciseSessionRecord         { exerciseType, title?, notes?, segmentCount, lapCount } (duration = end_time - start_time)
 *   HeartRateRecord               { samples: [{ time, beatsPerMinute }] }
 *   RestingHeartRateRecord        { restingHeartRateBpm: number }
 *   WeightRecord                  { weightKg: number }
 */

/**
 * Converts a local (Australia/Sydney) calendar date, or an exclusive range of
 * them, into the equivalent UTC instants for querying start_time. Used by
 * every date-scoped tool so "2026-07-05" means the same thing everywhere:
 * the Sydney calendar day, not the UTC one.
 */
export function localDateToUtc(date: string): Date {
  return DateTime.fromISO(date, { zone: USER_TIMEZONE }).startOf("day").toUTC().toJSDate();
}

function dayRangeUtc(date: string): { start: Date; end: Date } {
  const start = localDateToUtc(date);
  const end = DateTime.fromISO(date, { zone: USER_TIMEZONE }).startOf("day").plus({ days: 1 }).toUTC().toJSDate();
  return { start, end };
}

function inRange(start: Date, end: Date) {
  return and(gte(healthRecords.startTime, start), lt(healthRecords.startTime, end), isNull(healthRecords.deletedAt));
}

async function sumField(recordType: string, field: string, start: Date, end: Date): Promise<number | null> {
  const rows = await db
    .select({
      total: sql<string>`coalesce(sum((${healthRecords.data}->>${field})::numeric), 0)`,
    })
    .from(healthRecords)
    .where(and(eq(healthRecords.recordType, recordType), inRange(start, end)));
  const total = rows[0]?.total;
  return total === undefined ? null : Number(total);
}

/** Sums (end_time - start_time) in minutes for an interval record type, e.g. sleep/exercise duration. */
async function sumDurationMinutes(recordType: string, start: Date, end: Date): Promise<number | null> {
  const rows = await db
    .select({
      total: sql<string>`coalesce(sum(extract(epoch from (${healthRecords.endTime} - ${healthRecords.startTime})) / 60), 0)`,
    })
    .from(healthRecords)
    .where(and(eq(healthRecords.recordType, recordType), inRange(start, end)));
  const total = rows[0]?.total;
  return total === undefined ? null : Number(total);
}

export interface DailySummary {
  date: string;
  steps: number | null;
  distanceMeters: number | null;
  activeCaloriesKcal: number | null;
  totalCaloriesKcal: number | null;
  floorsClimbed: number | null;
  hydrationLiters: number | null;
  sleepMinutes: number | null;
  restingHeartRateBpm: number | null;
  exerciseSessions: Array<{ exerciseType: unknown; title: unknown; durationMinutes: number | null; startTime: string; endTime: string | null }>;
  latestWeightKg: number | null;
}

export async function getDailySummary(date: string): Promise<DailySummary> {
  const { start, end } = dayRangeUtc(date);

  const [
    steps,
    distanceMeters,
    activeCaloriesKcal,
    totalCaloriesKcal,
    floorsClimbed,
    hydrationLiters,
    sleepMinutes,
    restingHeartRateBpm,
    exerciseRows,
    latestWeightRows,
  ] = await Promise.all([
    sumField("StepsRecord", "count", start, end),
    sumField("DistanceRecord", "distanceMeters", start, end),
    sumField("ActiveCaloriesBurnedRecord", "energyKcal", start, end),
    sumField("TotalCaloriesBurnedRecord", "energyKcal", start, end),
    sumField("FloorsClimbedRecord", "floors", start, end),
    sumField("HydrationRecord", "volumeLiters", start, end),
    sumDurationMinutes("SleepSessionRecord", start, end),
    db
      .select({ bpm: sql<string>`avg((${healthRecords.data}->>'restingHeartRateBpm')::numeric)` })
      .from(healthRecords)
      .where(and(eq(healthRecords.recordType, "RestingHeartRateRecord"), inRange(start, end)))
      .then((rows) => (rows[0]?.bpm !== undefined ? Number(rows[0].bpm) : null)),
    db
      .select({
        data: healthRecords.data,
        startTime: healthRecords.startTime,
        endTime: healthRecords.endTime,
      })
      .from(healthRecords)
      .where(and(eq(healthRecords.recordType, "ExerciseSessionRecord"), inRange(start, end))),
    db
      .select({ data: healthRecords.data })
      .from(healthRecords)
      .where(and(eq(healthRecords.recordType, "WeightRecord"), isNull(healthRecords.deletedAt), lt(healthRecords.startTime, end)))
      .orderBy(sql`${healthRecords.startTime} desc`)
      .limit(1),
  ]);

  const latestWeightKg = (() => {
    const raw = latestWeightRows[0]?.data as Record<string, unknown> | undefined;
    const value = raw?.weightKg;
    return typeof value === "number" ? value : null;
  })();

  return {
    date,
    steps,
    distanceMeters,
    activeCaloriesKcal,
    totalCaloriesKcal,
    floorsClimbed,
    hydrationLiters,
    sleepMinutes,
    restingHeartRateBpm,
    exerciseSessions: exerciseRows.map((row) => {
      const data = row.data as Record<string, unknown>;
      const durationMinutes = row.endTime ? (row.endTime.getTime() - row.startTime.getTime()) / 60_000 : null;
      return {
        exerciseType: data.exerciseType,
        title: data.title,
        durationMinutes,
        startTime: row.startTime.toISOString(),
        endTime: row.endTime ? row.endTime.toISOString() : null,
      };
    }),
    latestWeightKg,
  };
}
