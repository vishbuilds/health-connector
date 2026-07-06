import { NextRequest, NextResponse } from "next/server";
import { runReadOnlyQuery } from "@/db/client";
import { isDeviceAuthorized } from "@/lib/device-auth";
import { getGoals, type Goals } from "@/lib/goals";

/**
 * Device-facing Home data: today's four headline metrics (steps, sleep, hydration, active
 * calories) with their goals + progress, plus a short list of deterministic, rule-based insight
 * strings. Backs the Android Home screen. Authenticated with the same INGEST_SECRET bearer as
 * /api/ingest and /api/writes (see lib/device-auth.ts) — no new principal or env var.
 *
 * Everything is computed server-side so the phone stays thin. "Today" and all day-bucketing are
 * in the user's timezone; metrics are de-duplicated across source apps using the same
 * sum-per-source-then-max-across pattern documented in mcp-tools.ts QUERY_DESCRIPTION.
 */
const USER_TIMEZONE = "Australia/Sydney";

/**
 * One combined aggregate: today's deduped totals, 7-day averages (steps, sleep), and resting-HR
 * this-week vs prior-week averages for the delta rules. Single-row result. The timezone is a
 * hard-coded constant (no user input), so string-interpolating it is safe.
 */
const HOME_QUERY = `
WITH params AS (
  SELECT (now() AT TIME ZONE '${USER_TIMEZONE}')::date AS today
),
steps_today AS (
  SELECT COALESCE(max(t.total), 0) AS v FROM (
    SELECT source_app, sum((data->>'count')::numeric) AS total
    FROM health_records, params
    WHERE record_type = 'StepsRecord' AND deleted_at IS NULL
      AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
    GROUP BY source_app
  ) t
),
active_today AS (
  SELECT COALESCE(max(t.total), 0) AS v FROM (
    SELECT source_app, sum((data->>'energyKcal')::numeric) AS total
    FROM health_records, params
    WHERE record_type = 'ActiveCaloriesBurnedRecord' AND deleted_at IS NULL
      AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
    GROUP BY source_app
  ) t
),
hydration_today AS (
  SELECT COALESCE(max(t.total), 0) AS v FROM (
    SELECT source_app, sum((data->>'volumeLiters')::numeric) AS total
    FROM health_records, params
    WHERE record_type = 'HydrationRecord' AND deleted_at IS NULL
      AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
    GROUP BY source_app
  ) t
),
sleep_today AS (
  -- Sleep is attributed to its local END (wake) date, so "last night" reads on today's card.
  SELECT COALESCE(max(t.total), 0) AS v FROM (
    SELECT source_app, sum(EXTRACT(EPOCH FROM (end_time - start_time)) / 60) AS total
    FROM health_records, params
    WHERE record_type = 'SleepSessionRecord' AND deleted_at IS NULL AND end_time IS NOT NULL
      AND (end_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
    GROUP BY source_app
  ) t
),
steps_7d AS (
  SELECT avg(day_total) AS v FROM (
    SELECT day, max(src_total) AS day_total FROM (
      SELECT (start_time AT TIME ZONE '${USER_TIMEZONE}')::date AS day, source_app,
             sum((data->>'count')::numeric) AS src_total
      FROM health_records, params
      WHERE record_type = 'StepsRecord' AND deleted_at IS NULL
        AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date BETWEEN params.today - 6 AND params.today
      GROUP BY 1, source_app
    ) s GROUP BY day
  ) d
),
sleep_7d AS (
  SELECT avg(day_total) AS v FROM (
    SELECT day, max(src_total) AS day_total FROM (
      SELECT (end_time AT TIME ZONE '${USER_TIMEZONE}')::date AS day, source_app,
             sum(EXTRACT(EPOCH FROM (end_time - start_time)) / 60) AS src_total
      FROM health_records, params
      WHERE record_type = 'SleepSessionRecord' AND deleted_at IS NULL AND end_time IS NOT NULL
        AND (end_time AT TIME ZONE '${USER_TIMEZONE}')::date BETWEEN params.today - 6 AND params.today
      GROUP BY 1, source_app
    ) s GROUP BY day
  ) d
),
rhr_this AS (
  SELECT avg((data->>'restingHeartRateBpm')::numeric) AS v
  FROM health_records, params
  WHERE record_type = 'RestingHeartRateRecord' AND deleted_at IS NULL
    AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date BETWEEN params.today - 6 AND params.today
),
rhr_prior AS (
  SELECT avg((data->>'restingHeartRateBpm')::numeric) AS v
  FROM health_records, params
  WHERE record_type = 'RestingHeartRateRecord' AND deleted_at IS NULL
    AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date BETWEEN params.today - 13 AND params.today - 7
)
SELECT
  params.today::text        AS date,
  steps_today.v             AS steps_today,
  active_today.v            AS active_today,
  hydration_today.v         AS hydration_today,
  sleep_today.v             AS sleep_today,
  steps_7d.v                AS steps_7d,
  sleep_7d.v                AS sleep_7d,
  rhr_this.v                AS rhr_this,
  rhr_prior.v               AS rhr_prior
FROM params, steps_today, active_today, hydration_today, sleep_today, steps_7d, sleep_7d, rhr_this, rhr_prior
`;

/** Numeric columns come back from the pg driver as strings (or null); coerce, treating null as null. */
function num(v: unknown): number | null {
  if (v === null || v === undefined) return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

interface Metric {
  value: number;
  goal: number;
  progress: number;
  unit?: string;
}

function metric(value: number, goal: number, unit?: string): Metric {
  const progress = goal > 0 ? Math.min(1, Math.round((value / goal) * 100) / 100) : 0;
  return unit ? { value, goal, progress, unit } : { value, goal, progress };
}

/**
 * Deterministic, rule-based insight strings from the aggregate row + goals. Goal-gap rules come
 * first; the list is capped at 5. Every rule guards against null/missing data (skip, never emit
 * NaN). Nothing here calls an LLM.
 */
function buildInsights(
  row: Record<string, unknown>,
  goals: Goals,
  steps: number,
  sleepMin: number,
  hydration: number,
  activeCal: number,
): string[] {
  const insights: string[] = [];
  const fmt = (n: number) => Math.round(n).toLocaleString("en-US");

  // 1. Steps gap
  if (steps >= goals.stepsTarget) {
    insights.push(`Steps goal hit — ${fmt(steps)}`);
  } else {
    insights.push(`${fmt(goals.stepsTarget - steps)} steps to go to hit your ${fmt(goals.stepsTarget)} goal`);
  }

  // 2. Hydration gap
  if (hydration < goals.hydrationLitersTarget) {
    const gap = Math.round((goals.hydrationLitersTarget - hydration) * 10) / 10;
    insights.push(`${gap}L of water to go`);
  }

  // 3. Active-calories gap
  if (activeCal < goals.activeCaloriesTarget) {
    insights.push(`${fmt(goals.activeCaloriesTarget - activeCal)} active calories to go`);
  }

  // 4. Sleep vs 7-day average
  const sleep7d = num(row.sleep_7d);
  if (sleep7d !== null && sleep7d > 0) {
    const delta = Math.round(sleepMin - sleep7d);
    if (Math.abs(delta) > 15) {
      insights.push(`Sleep ${Math.abs(delta)} min ${delta < 0 ? "below" : "above"} your 7-day average`);
    }
  }

  // 5. Resting HR week-over-week
  const rhrThis = num(row.rhr_this);
  const rhrPrior = num(row.rhr_prior);
  if (rhrThis !== null && rhrPrior !== null && rhrPrior > 0) {
    const pct = Math.round(((rhrThis - rhrPrior) / rhrPrior) * 100);
    if (Math.abs(pct) >= 5) {
      insights.push(`Resting HR ${pct > 0 ? "up" : "down"} ${Math.abs(pct)}% vs last week`);
    }
  }

  // 6. Steps momentum vs 7-day average
  const steps7d = num(row.steps_7d);
  if (steps7d !== null && steps7d > 0) {
    const pct = Math.round(((steps - steps7d) / steps7d) * 100);
    if (pct !== 0) {
      insights.push(`${Math.abs(pct)}% ${pct > 0 ? "ahead of" : "behind"} your typical day`);
    }
  }

  return insights.slice(0, 5);
}

export async function GET(request: NextRequest) {
  if (!isDeviceAuthorized(request)) {
    return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  }

  let row: Record<string, unknown>;
  try {
    const rows = await runReadOnlyQuery(HOME_QUERY);
    row = rows[0] ?? {};
  } catch (e) {
    return NextResponse.json({ error: `query failed: ${(e as Error).message}` }, { status: 500 });
  }

  const goals = await getGoals();

  const steps = num(row.steps_today) ?? 0;
  const sleepMin = Math.round(num(row.sleep_today) ?? 0);
  const hydration = Math.round((num(row.hydration_today) ?? 0) * 100) / 100;
  const activeCal = Math.round(num(row.active_today) ?? 0);

  return NextResponse.json({
    date: (row.date as string) ?? null,
    metrics: {
      steps: metric(Math.round(steps), goals.stepsTarget),
      sleep: metric(sleepMin, goals.sleepMinutesTarget, "minutes"),
      hydration: metric(hydration, goals.hydrationLitersTarget, "liters"),
      activeCalories: metric(activeCal, goals.activeCaloriesTarget),
    },
    insights: buildInsights(row, goals, Math.round(steps), sleepMin, hydration, activeCal),
  });
}
