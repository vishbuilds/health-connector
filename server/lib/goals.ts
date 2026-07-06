import { eq } from "drizzle-orm";
import { db } from "@/db/client";
import { userGoals } from "@/db/schema";

/**
 * Daily goals as consumed by the app and Claude. All numeric — `hydrationLiters` is a real
 * number here even though the column is Postgres `numeric` (which drizzle returns as a string).
 */
export interface Goals {
  stepsTarget: number;
  sleepMinutesTarget: number;
  hydrationLitersTarget: number;
  // Body-recomposition levers (drive the Home screen's weight-goal view).
  proteinGramsTarget: number;
  weeklyWorkoutTarget: number;
  weightTargetKg: number | null; // null = no target weight set
}

/**
 * Mirrors the column defaults in db/schema.ts::userGoals. Used as the fallback when the single
 * "default" row hasn't been written yet, so the table is lazy — no seed row needed.
 */
export const DEFAULT_GOALS: Goals = {
  stepsTarget: 10000,
  sleepMinutesTarget: 480, // 8h
  hydrationLitersTarget: 2.5,
  proteinGramsTarget: 140,
  weeklyWorkoutTarget: 4,
  weightTargetKg: null,
};

/**
 * Reads the single "default" goals row, coercing the `numeric` hydration column to a JS number,
 * and falls back to DEFAULT_GOALS when the row is absent. Shared by the MCP layer (mcp-tools.ts)
 * and GET /api/home so neither has to import the other.
 */
export async function getGoals(): Promise<Goals> {
  let row: typeof userGoals.$inferSelect | undefined;
  try {
    [row] = await db.select().from(userGoals).where(eq(userGoals.id, "default")).limit(1);
  } catch (error) {
    console.warn("Falling back to default goals; user_goals query failed.", error);
    return { ...DEFAULT_GOALS };
  }
  if (!row) return { ...DEFAULT_GOALS };
  return {
    stepsTarget: row.stepsTarget,
    sleepMinutesTarget: row.sleepMinutesTarget,
    hydrationLitersTarget: Number(row.hydrationLitersTarget),
    proteinGramsTarget: row.proteinGramsTarget,
    weeklyWorkoutTarget: row.weeklyWorkoutTarget,
    weightTargetKg: row.weightTargetKg === null ? null : Number(row.weightTargetKg),
  };
}
