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
  activeCaloriesTarget: number;
}

/**
 * Mirrors the column defaults in db/schema.ts::userGoals. Used as the fallback when the single
 * "default" row hasn't been written yet, so the table is lazy — no seed row needed.
 */
export const DEFAULT_GOALS: Goals = {
  stepsTarget: 10000,
  sleepMinutesTarget: 480, // 8h
  hydrationLitersTarget: 2.5,
  activeCaloriesTarget: 500,
};

/**
 * Reads the single "default" goals row, coercing the `numeric` hydration column to a JS number,
 * and falls back to DEFAULT_GOALS when the row is absent. Shared by the MCP layer (mcp-tools.ts)
 * and GET /api/home so neither has to import the other.
 */
export async function getGoals(): Promise<Goals> {
  const [row] = await db.select().from(userGoals).where(eq(userGoals.id, "default")).limit(1);
  if (!row) return { ...DEFAULT_GOALS };
  return {
    stepsTarget: row.stepsTarget,
    sleepMinutesTarget: row.sleepMinutesTarget,
    hydrationLitersTarget: Number(row.hydrationLitersTarget),
    activeCaloriesTarget: row.activeCaloriesTarget,
  };
}
