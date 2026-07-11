import { NextRequest, NextResponse } from "next/server";
import { runReadOnlyQuery } from "@/db/client";
import { isDeviceAuthorized } from "@/lib/device-auth";
import { getGoals, type Goals } from "@/lib/goals";
import { deriveBmrKcalPerDay, getUserProfile } from "@/lib/profile";

/**
 * Device-facing Home data for a bodyweight / body-recomposition goal. The screen is built around one
 * dominant signal — the bodyweight trend (the actual scoreboard for recomp) — plus four supporting
 * "levers", shown in this order: calorie balance (deficit/surplus, daily), protein (daily),
 * training (weekly sessions), and steps (daily). Everything is computed server-side so the phone
 * stays thin. Authenticated with the same
 * INGEST_SECRET bearer as /api/ingest and /api/writes (see lib/device-auth.ts).
 *
 * "Today" and all day-bucketing are in the user's timezone; summed metrics are de-duplicated across
 * source apps using the sum-per-source-then-max-across pattern (a phone pedometer + a ring log the
 * same activity), documented in mcp-tools.ts QUERY_DESCRIPTION.
 */
const USER_TIMEZONE = "Australia/Sydney";

/** Days of daily weight points to fetch for the sparkline + trend fit. */
const WEIGHT_WINDOW_DAYS = 28;
const META_WINDOW_DAYS = 14;
const CALORIE_FORECAST_LOOKBACK_DAYS = 7;
const BODY_WEIGHT_CHANGE_FRACTION_PER_WEEK = 0.005; // 0.5% body weight / week
const KCAL_PER_KG_BODY_WEIGHT = 7700;
const TARGET_WEIGHT_TOLERANCE_KG = 0.2;

const numericSql = (jsonTextExpr: string) =>
  `CASE WHEN (${jsonTextExpr}) ~ '^-?[0-9]+([.][0-9]+)?$' THEN (${jsonTextExpr})::numeric END`;

/**
 * One combined aggregate: today's deduped steps + protein + calorie balance inputs, this-week
 * workout sessions, and the latest body-fat reading. Daily expenditure is intentionally
 * derived in TypeScript as active calories + basal burn; raw TotalCaloriesBurnedRecord rows are
 * interval observations and are not assumed to represent full-day expenditure. All aggregate CTEs return
 * exactly one row; the body-fat reading is a scalar subselect so an absent reading doesn't drop the
 * whole row. The timezone is a hard-coded constant (no user input), so interpolating it is safe.
 */
const homeQuery = (targetDate: string) => `
WITH params AS (
  SELECT DATE '${targetDate}' AS today
),
actual_records AS (
  SELECT hr.record_type, hr.start_time, hr.end_time, hr.data, hr.source_app
  FROM health_records hr
  WHERE hr.deleted_at IS NULL
    AND NOT EXISTS (
      SELECT 1
      FROM pending_writes pw
      WHERE pw.status = 'pending'
        AND pw.health_connect_id IS NOT NULL
        AND pw.health_connect_id = hr.id
    )
),
pending_records AS (
  SELECT pw.record_type, pw.start_time, pw.end_time, pw.data, '__pending_writes__'::text AS source_app
  FROM pending_writes pw
  WHERE (
      pw.status = 'pending'
      OR pw.status = 'applied'
    )
    AND NOT EXISTS (
      SELECT 1
      FROM health_records hr
      WHERE hr.deleted_at IS NULL
        AND pw.health_connect_id IS NOT NULL
        AND hr.id = pw.health_connect_id
        AND pw.status = 'applied'
    )
),
combined_records AS (
  SELECT * FROM actual_records
  UNION ALL
  SELECT * FROM pending_records
),
steps_today AS (
  SELECT
    COALESCE((
      SELECT max(t.total) FROM (
        SELECT source_app, sum(${numericSql("data->>'count'")}) AS total
        FROM actual_records, params
        WHERE record_type = 'StepsRecord'
          AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
        GROUP BY source_app
      ) t
    ), 0)
    + COALESCE((
      SELECT sum(${numericSql("data->>'count'")})
      FROM pending_records, params
      WHERE record_type = 'StepsRecord'
        AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
    ), 0) AS v
),
protein_today AS (
  SELECT
    COALESCE((
      SELECT max(t.total) FROM (
        SELECT source_app, sum(${numericSql("data->>'proteinGrams'")}) AS total
        FROM actual_records, params
        WHERE record_type = 'NutritionRecord'
          AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
        GROUP BY source_app
      ) t
    ), 0)
    + COALESCE((
      SELECT sum(${numericSql("data->>'proteinGrams'")})
      FROM pending_records, params
      WHERE record_type = 'NutritionRecord'
        AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
    ), 0) AS v
),
training_week AS (
  -- Deliberately-logged workouts over the trailing 7 days (a rolling "this week"). We must NOT
  -- count every ExerciseSessionRecord, because wearables like Oura auto-detect and log passive
  -- activity (e.g. each walk becomes an ExerciseSession, ~15/week) which is not "training". A
  -- training session is: anything logged via Hevy (strength work), or a clear run logged via
  -- Strava. These come from distinct apps that don't double-log the same workout, so we count
  -- all qualifying sessions rather than de-duplicating across sources.
  SELECT count(*) AS v
  FROM actual_records, params
  WHERE record_type = 'ExerciseSessionRecord'
    AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date BETWEEN params.today - 6 AND params.today
    AND (
      source_app = 'com.hevy'
      -- Strava runs only (56 = running, 57 = running on treadmill); ignore auto-logged walks/rides.
      OR (source_app LIKE '%strava%' AND (data->>'exerciseType')::int IN (56, 57))
    )
),
active_energy_today AS (
  -- Active calories burned today, de-duplicated across source apps the same way steps are (a phone
  -- and a wearable both log the burn): sum per source, then take the busiest single source.
  SELECT COALESCE(max(t.total), 0) AS v FROM (
    SELECT source_app, sum(${numericSql("data->>'energyKcal'")}) AS total
    FROM actual_records, params
    WHERE record_type = 'ActiveCaloriesBurnedRecord'
      AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
    GROUP BY source_app
  ) t
),
workout_energy_today AS (
  -- Strength-training burn that Hevy logs as a TotalCaloriesBurnedRecord scoped to each workout
  -- session (not a full-day feed). Oura's accelerometer barely registers lifting, so this is real
  -- expenditure that active_energy_today misses; add it on top. De-dup across sources the same way
  -- (sum per source, take the busiest) — today Hevy is the only writer of this record type.
  SELECT COALESCE(max(t.total), 0) AS v FROM (
    SELECT source_app, sum(${numericSql("data->>'energyKcal'")}) AS total
    FROM actual_records, params
    WHERE record_type = 'TotalCaloriesBurnedRecord'
      AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
    GROUP BY source_app
  ) t
),
nutrition_energy_today AS (
  SELECT
    COALESCE((
      SELECT max(t.total) FROM (
        SELECT source_app, sum(${numericSql("data->>'energyKcal'")}) AS total
        FROM actual_records, params
        WHERE record_type = 'NutritionRecord'
          AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
        GROUP BY source_app
      ) t
    ), 0)
    + COALESCE((
      SELECT sum(${numericSql("data->>'energyKcal'")})
      FROM pending_records, params
      WHERE record_type = 'NutritionRecord'
        AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
    ), 0) AS v
),
steps_7d AS (
  SELECT avg(day_total) AS v FROM (
    SELECT day, COALESCE(max(src_total), 0) + COALESCE(max(pending_total), 0) AS day_total FROM (
      SELECT (start_time AT TIME ZONE '${USER_TIMEZONE}')::date AS day, source_app,
             sum(${numericSql("data->>'count'")}) AS src_total,
             NULL::numeric AS pending_total
      FROM actual_records, params
      WHERE record_type = 'StepsRecord'
        AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date BETWEEN params.today - 6 AND params.today
      GROUP BY 1, source_app
      UNION ALL
      SELECT (start_time AT TIME ZONE '${USER_TIMEZONE}')::date AS day, '__pending_writes__'::text AS source_app,
             NULL::numeric AS src_total,
             sum(${numericSql("data->>'count'")}) AS pending_total
      FROM pending_records, params
      WHERE record_type = 'StepsRecord'
        AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date BETWEEN params.today - 6 AND params.today
      GROUP BY 1
    ) s GROUP BY day
  ) d
)
SELECT
  params.today::text  AS date,
  steps_today.v       AS steps_today,
  protein_today.v     AS protein_today,
  training_week.v     AS training_week,
  active_energy_today.v AS active_energy_today,
  workout_energy_today.v AS workout_energy_today,
  nutrition_energy_today.v AS nutrition_energy_today,
  steps_7d.v          AS steps_7d,
  (SELECT ${numericSql("data->>'basalMetabolicRateKcalPerDay'")}
     FROM combined_records, params
     WHERE record_type = 'BasalMetabolicRateRecord'
       AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date <= params.today
     ORDER BY start_time DESC LIMIT 1)              AS latest_bmr,
  (SELECT ${numericSql("data->>'weightKg'")}
     FROM combined_records, params
     WHERE record_type = 'WeightRecord'
       AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date <= params.today
     ORDER BY start_time DESC LIMIT 1)              AS latest_weight,
  (SELECT ${numericSql("data->>'bodyFatPercentage'")}
     FROM combined_records, params
     WHERE record_type = 'BodyFatRecord'
       AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date <= params.today
     ORDER BY start_time DESC LIMIT 1)              AS body_fat,
  (SELECT sum(${numericSql("data->>'proteinGrams'")})
     FROM combined_records, params
     WHERE record_type = 'NutritionRecord'
       AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today - 1) AS protein_yesterday
	FROM params, steps_today, protein_today, training_week, active_energy_today, workout_energy_today, nutrition_energy_today, steps_7d
`;
/**
 * One row per local day (most-recent first) with a body weight, over the trend window. Multiple
 * scales/manual entries on the same day are averaged. Days without a weigh-in are simply absent —
 * the sparkline connects the points it has and the trend fit uses day offsets, so gaps are fine.
 */
const weightSeriesQuery = (targetDate: string) => `
WITH params AS (
  SELECT DATE '${targetDate}' AS today
),
combined_records AS (
  SELECT hr.record_type, hr.start_time, hr.data
  FROM health_records hr
  WHERE hr.deleted_at IS NULL
    AND NOT EXISTS (
      SELECT 1
      FROM pending_writes pw
      WHERE pw.status = 'pending'
        AND pw.health_connect_id IS NOT NULL
        AND pw.health_connect_id = hr.id
    )
  UNION ALL
  SELECT record_type, start_time, data
  FROM pending_writes pw
  WHERE (
      pw.status = 'pending'
      OR pw.status = 'applied'
    )
    AND NOT EXISTS (
      SELECT 1
      FROM health_records hr
      WHERE hr.deleted_at IS NULL
        AND pw.health_connect_id IS NOT NULL
        AND hr.id = pw.health_connect_id
        AND pw.status = 'applied'
    )
)
SELECT
  (start_time AT TIME ZONE '${USER_TIMEZONE}')::date::text AS day,
  avg(${numericSql("data->>'weightKg'")})                  AS kg
FROM combined_records, params
WHERE record_type = 'WeightRecord'
  AND (data->>'weightKg') IS NOT NULL
  AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date BETWEEN params.today - ${WEIGHT_WINDOW_DAYS - 1} AND params.today
GROUP BY 1
ORDER BY 1 DESC
`;

/**
 * One row per local day for the recovery score. Sleep is assigned to the day it ends,
 * matching the way a sleep score is usually consumed in the morning. Summed interval metrics are
 * de-duplicated per day by source_app using the same sum-per-source, max-across-source pattern as
 * the main Home levers.
 */
const recoverySeriesQuery = (targetDate: string) => `
WITH params AS (
  SELECT DATE '${targetDate}' AS today
),
actual_records AS (
  SELECT hr.record_type, hr.start_time, hr.end_time, hr.data, hr.source_app
  FROM health_records hr
  WHERE hr.deleted_at IS NULL
    AND NOT EXISTS (
      SELECT 1
      FROM pending_writes pw
      WHERE pw.status = 'pending'
        AND pw.health_connect_id IS NOT NULL
        AND pw.health_connect_id = hr.id
    )
),
pending_records AS (
  SELECT pw.record_type, pw.start_time, pw.end_time, pw.data, '__pending_writes__'::text AS source_app
  FROM pending_writes pw
  WHERE (
      pw.status = 'pending'
      OR pw.status = 'applied'
    )
    AND NOT EXISTS (
      SELECT 1
      FROM health_records hr
      WHERE hr.deleted_at IS NULL
        AND pw.health_connect_id IS NOT NULL
        AND hr.id = pw.health_connect_id
        AND pw.status = 'applied'
    )
),
combined_records AS (
  SELECT * FROM actual_records
  UNION ALL
  SELECT * FROM pending_records
),
days AS (
  SELECT generate_series(params.today - ${META_WINDOW_DAYS - 1}, params.today, interval '1 day')::date AS day
  FROM params
),
sleep_daily AS (
  SELECT day, COALESCE(max(total), 0) AS sleep_minutes FROM (
    SELECT d.day, hr.source_app, sum(extract(epoch FROM (hr.end_time - hr.start_time)) / 60.0) AS total
    FROM days d
    LEFT JOIN combined_records hr
      ON hr.record_type = 'SleepSessionRecord'
     AND hr.end_time IS NOT NULL
     AND (hr.end_time AT TIME ZONE '${USER_TIMEZONE}')::date = d.day
    GROUP BY d.day, hr.source_app
  ) t
  GROUP BY day
),
active_energy_daily AS (
  SELECT day, COALESCE(max(total), 0) AS active_energy FROM (
    SELECT d.day, hr.source_app, sum(${numericSql("hr.data->>'energyKcal'")}) AS total
    FROM days d
    LEFT JOIN actual_records hr
      ON hr.record_type = 'ActiveCaloriesBurnedRecord'
     AND (hr.start_time AT TIME ZONE '${USER_TIMEZONE}')::date = d.day
    GROUP BY d.day, hr.source_app
  ) t
  GROUP BY day
),
hrv_daily AS (
  SELECT d.day, avg(${numericSql("hr.data->>'heartRateVariabilityMillis'")}) AS hrv_ms
  FROM days d
  LEFT JOIN combined_records hr
    ON hr.record_type = 'HeartRateVariabilityRmssdRecord'
   AND (hr.start_time AT TIME ZONE '${USER_TIMEZONE}')::date = d.day
   AND (hr.data->>'heartRateVariabilityMillis') IS NOT NULL
  GROUP BY d.day
),
rhr_daily AS (
  SELECT d.day, avg(${numericSql("hr.data->>'restingHeartRateBpm'")}) AS rhr_bpm
  FROM days d
  LEFT JOIN combined_records hr
    ON hr.record_type = 'RestingHeartRateRecord'
   AND (hr.start_time AT TIME ZONE '${USER_TIMEZONE}')::date = d.day
   AND (hr.data->>'restingHeartRateBpm') IS NOT NULL
  GROUP BY d.day
)
SELECT
  d.day::text AS date,
  sleep_daily.sleep_minutes,
  active_energy_daily.active_energy,
  hrv_daily.hrv_ms,
  rhr_daily.rhr_bpm
FROM days d
LEFT JOIN sleep_daily ON sleep_daily.day = d.day
LEFT JOIN active_energy_daily ON active_energy_daily.day = d.day
LEFT JOIN hrv_daily ON hrv_daily.day = d.day
LEFT JOIN rhr_daily ON rhr_daily.day = d.day
ORDER BY d.day ASC
`;

const remainingIntakeTrendQuery = (targetDate: string, afterMinute: number) => {
  const cutoffMinute = Math.max(0, Math.min(24 * 60 - 1, Math.trunc(afterMinute)));
  return `
WITH params AS (
  SELECT DATE '${targetDate}' AS today, ${cutoffMinute}::int AS cutoff_minute
),
actual_records AS (
  SELECT hr.record_type, hr.start_time, hr.data, hr.source_app
  FROM health_records hr
  WHERE hr.deleted_at IS NULL
    AND NOT EXISTS (
      SELECT 1
      FROM pending_writes pw
      WHERE pw.status = 'pending'
        AND pw.health_connect_id IS NOT NULL
        AND pw.health_connect_id = hr.id
    )
),
pending_records AS (
  SELECT pw.record_type, pw.start_time, pw.data, '__pending_writes__'::text AS source_app
  FROM pending_writes pw
  WHERE (
      pw.status = 'pending'
      OR pw.status = 'applied'
    )
    AND NOT EXISTS (
      SELECT 1
      FROM health_records hr
      WHERE hr.deleted_at IS NULL
        AND pw.health_connect_id IS NOT NULL
        AND hr.id = pw.health_connect_id
        AND pw.status = 'applied'
    )
),
days AS (
  SELECT generate_series(params.today - ${CALORIE_FORECAST_LOOKBACK_DAYS}, params.today - 1, interval '1 day')::date AS day
  FROM params
),
actual_food_daily AS (
  SELECT
    (start_time AT TIME ZONE '${USER_TIMEZONE}')::date AS day,
    source_app,
    sum(${numericSql("data->>'energyKcal'")}) AS total
  FROM actual_records, params
  WHERE record_type = 'NutritionRecord'
    AND ${numericSql("data->>'energyKcal'")} IS NOT NULL
    AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date BETWEEN params.today - ${CALORIE_FORECAST_LOOKBACK_DAYS} AND params.today - 1
  GROUP BY 1, source_app
),
pending_food_daily AS (
  SELECT
    (start_time AT TIME ZONE '${USER_TIMEZONE}')::date AS day,
    sum(${numericSql("data->>'energyKcal'")}) AS total
  FROM pending_records, params
  WHERE record_type = 'NutritionRecord'
    AND ${numericSql("data->>'energyKcal'")} IS NOT NULL
    AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date BETWEEN params.today - ${CALORIE_FORECAST_LOOKBACK_DAYS} AND params.today - 1
  GROUP BY 1
),
food_days AS (
  SELECT
    days.day,
    COALESCE((SELECT max(total) FROM actual_food_daily WHERE actual_food_daily.day = days.day), 0)
      + COALESCE((SELECT sum(total) FROM pending_food_daily WHERE pending_food_daily.day = days.day), 0) AS total
  FROM days
),
actual_daily AS (
  SELECT
    (start_time AT TIME ZONE '${USER_TIMEZONE}')::date AS day,
    CASE
      WHEN (data->>'mealType') ~ '^[0-9]+$' AND (data->>'mealType')::int = 3 THEN 'dinner'
      WHEN lower(COALESCE(data->>'name', '')) ~ '(dinner|supper|evening)' THEN 'dinner'
      WHEN (
        extract(hour FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int * 60
        + extract(minute FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int
      ) >= 17 * 60 THEN 'dinner'
      WHEN (data->>'mealType') ~ '^[0-9]+$' AND (data->>'mealType')::int = 2 THEN 'lunch'
      WHEN lower(COALESCE(data->>'name', '')) ~ '(lunch)' THEN 'lunch'
      WHEN (
        extract(hour FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int * 60
        + extract(minute FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int
      ) BETWEEN 11 * 60 AND 17 * 60 - 1 THEN 'lunch'
      WHEN (data->>'mealType') ~ '^[0-9]+$' AND (data->>'mealType')::int = 1 THEN 'breakfast'
      WHEN lower(COALESCE(data->>'name', '')) ~ '(breakfast|brunch)' THEN 'breakfast'
      WHEN (
        extract(hour FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int * 60
        + extract(minute FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int
      ) < 11 * 60 THEN 'breakfast'
      ELSE 'snack'
    END AS bucket,
    source_app,
    sum(${numericSql("data->>'energyKcal'")}) AS total
  FROM actual_records, params
  WHERE record_type = 'NutritionRecord'
    AND ${numericSql("data->>'energyKcal'")} IS NOT NULL
    AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date BETWEEN params.today - ${CALORIE_FORECAST_LOOKBACK_DAYS} AND params.today - 1
    AND (
      extract(hour FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int * 60
      + extract(minute FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int
    ) > params.cutoff_minute
  GROUP BY 1, 2, source_app
),
pending_daily AS (
  SELECT
    (start_time AT TIME ZONE '${USER_TIMEZONE}')::date AS day,
    CASE
      WHEN (data->>'mealType') ~ '^[0-9]+$' AND (data->>'mealType')::int = 3 THEN 'dinner'
      WHEN lower(COALESCE(data->>'name', '')) ~ '(dinner|supper|evening)' THEN 'dinner'
      WHEN (
        extract(hour FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int * 60
        + extract(minute FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int
      ) >= 17 * 60 THEN 'dinner'
      WHEN (data->>'mealType') ~ '^[0-9]+$' AND (data->>'mealType')::int = 2 THEN 'lunch'
      WHEN lower(COALESCE(data->>'name', '')) ~ '(lunch)' THEN 'lunch'
      WHEN (
        extract(hour FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int * 60
        + extract(minute FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int
      ) BETWEEN 11 * 60 AND 17 * 60 - 1 THEN 'lunch'
      WHEN (data->>'mealType') ~ '^[0-9]+$' AND (data->>'mealType')::int = 1 THEN 'breakfast'
      WHEN lower(COALESCE(data->>'name', '')) ~ '(breakfast|brunch)' THEN 'breakfast'
      WHEN (
        extract(hour FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int * 60
        + extract(minute FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int
      ) < 11 * 60 THEN 'breakfast'
      ELSE 'snack'
    END AS bucket,
    sum(${numericSql("data->>'energyKcal'")}) AS total
  FROM pending_records, params
  WHERE record_type = 'NutritionRecord'
    AND ${numericSql("data->>'energyKcal'")} IS NOT NULL
    AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date BETWEEN params.today - ${CALORIE_FORECAST_LOOKBACK_DAYS} AND params.today - 1
    AND (
      extract(hour FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int * 60
      + extract(minute FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int
    ) > params.cutoff_minute
  GROUP BY 1, 2
),
actual_future AS (
  SELECT
    CASE
      WHEN (data->>'mealType') ~ '^[0-9]+$' AND (data->>'mealType')::int = 3 THEN 'dinner'
      WHEN lower(COALESCE(data->>'name', '')) ~ '(dinner|supper|evening)' THEN 'dinner'
      WHEN (
        extract(hour FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int * 60
        + extract(minute FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int
      ) >= 17 * 60 THEN 'dinner'
      WHEN (data->>'mealType') ~ '^[0-9]+$' AND (data->>'mealType')::int = 2 THEN 'lunch'
      WHEN lower(COALESCE(data->>'name', '')) ~ '(lunch)' THEN 'lunch'
      WHEN (
        extract(hour FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int * 60
        + extract(minute FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int
      ) BETWEEN 11 * 60 AND 17 * 60 - 1 THEN 'lunch'
      WHEN (data->>'mealType') ~ '^[0-9]+$' AND (data->>'mealType')::int = 1 THEN 'breakfast'
      WHEN lower(COALESCE(data->>'name', '')) ~ '(breakfast|brunch)' THEN 'breakfast'
      WHEN (
        extract(hour FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int * 60
        + extract(minute FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int
      ) < 11 * 60 THEN 'breakfast'
      ELSE 'snack'
    END AS bucket,
    source_app,
    sum(${numericSql("data->>'energyKcal'")}) AS total
  FROM actual_records, params
  WHERE record_type = 'NutritionRecord'
    AND ${numericSql("data->>'energyKcal'")} IS NOT NULL
    AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
    AND (
      extract(hour FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int * 60
      + extract(minute FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int
    ) > params.cutoff_minute
  GROUP BY 1, source_app
),
pending_future AS (
  SELECT
    CASE
      WHEN (data->>'mealType') ~ '^[0-9]+$' AND (data->>'mealType')::int = 3 THEN 'dinner'
      WHEN lower(COALESCE(data->>'name', '')) ~ '(dinner|supper|evening)' THEN 'dinner'
      WHEN (
        extract(hour FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int * 60
        + extract(minute FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int
      ) >= 17 * 60 THEN 'dinner'
      WHEN (data->>'mealType') ~ '^[0-9]+$' AND (data->>'mealType')::int = 2 THEN 'lunch'
      WHEN lower(COALESCE(data->>'name', '')) ~ '(lunch)' THEN 'lunch'
      WHEN (
        extract(hour FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int * 60
        + extract(minute FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int
      ) BETWEEN 11 * 60 AND 17 * 60 - 1 THEN 'lunch'
      WHEN (data->>'mealType') ~ '^[0-9]+$' AND (data->>'mealType')::int = 1 THEN 'breakfast'
      WHEN lower(COALESCE(data->>'name', '')) ~ '(breakfast|brunch)' THEN 'breakfast'
      WHEN (
        extract(hour FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int * 60
        + extract(minute FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int
      ) < 11 * 60 THEN 'breakfast'
      ELSE 'snack'
    END AS bucket,
    sum(${numericSql("data->>'energyKcal'")}) AS total
  FROM pending_records, params
  WHERE record_type = 'NutritionRecord'
    AND ${numericSql("data->>'energyKcal'")} IS NOT NULL
    AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
    AND (
      extract(hour FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int * 60
      + extract(minute FROM (start_time AT TIME ZONE '${USER_TIMEZONE}'))::int
    ) > params.cutoff_minute
  GROUP BY 1
),
future_buckets AS (
  SELECT
    bucket,
    COALESCE((SELECT max(total) FROM actual_future af WHERE af.bucket = b.bucket), 0)
      + COALESCE((SELECT sum(total) FROM pending_future pf WHERE pf.bucket = b.bucket), 0) AS total
  FROM (
    SELECT bucket FROM actual_future
    UNION
    SELECT bucket FROM pending_future
  ) b
),
historical_bucket_totals AS (
  SELECT day, bucket, max(total) AS total
  FROM actual_daily
  GROUP BY day, bucket
  UNION ALL
  SELECT day, bucket, sum(total) AS total
  FROM pending_daily
  GROUP BY day, bucket
),
day_totals AS (
  SELECT
    food_days.day,
    COALESCE(sum(historical_bucket_totals.total) FILTER (
      WHERE NOT EXISTS (
        SELECT 1
        FROM future_buckets
        WHERE future_buckets.bucket = historical_bucket_totals.bucket
          AND future_buckets.total > 0
      )
    ), 0) AS total
  FROM food_days
  LEFT JOIN historical_bucket_totals ON historical_bucket_totals.day = food_days.day
  WHERE food_days.total > 0
  GROUP BY food_days.day
)
SELECT
  avg(total) AS remaining_energy,
  count(*) AS sample_days,
  COALESCE((SELECT sum(total) FROM future_buckets), 0) AS planned_future_energy,
  COALESCE((SELECT count(*) FROM future_buckets WHERE total > 0), 0) AS planned_bucket_count
FROM day_totals
`;
};

const homeDetailRowsQuery = (targetDate: string) => `
WITH params AS (
  SELECT DATE '${targetDate}' AS today
),
actual_records AS (
  SELECT hr.record_type, hr.start_time, hr.end_time, hr.data, hr.source_app
  FROM health_records hr
  WHERE hr.deleted_at IS NULL
    AND NOT EXISTS (
      SELECT 1
      FROM pending_writes pw
      WHERE pw.status = 'pending'
        AND pw.health_connect_id IS NOT NULL
        AND pw.health_connect_id = hr.id
    )
),
pending_records AS (
  SELECT pw.record_type, pw.start_time, pw.end_time, pw.data, '__pending_writes__'::text AS source_app
  FROM pending_writes pw
  WHERE (
      pw.status = 'pending'
      OR pw.status = 'applied'
    )
    AND NOT EXISTS (
      SELECT 1
      FROM health_records hr
      WHERE hr.deleted_at IS NULL
        AND pw.health_connect_id IS NOT NULL
        AND hr.id = pw.health_connect_id
        AND pw.status = 'applied'
    )
),
combined_records AS (
  SELECT * FROM actual_records
  UNION ALL
  SELECT * FROM pending_records
),
step_source_totals AS (
  SELECT
    source_app,
    count(*) AS rows_count,
    sum(${numericSql("data->>'count'")}) AS total,
    min(start_time) AS first_start,
    max(end_time) AS last_end,
    false AS pending
  FROM actual_records, params
  WHERE record_type = 'StepsRecord'
    AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
  GROUP BY source_app
  UNION ALL
  SELECT
    source_app,
    count(*) AS rows_count,
    sum(${numericSql("data->>'count'")}) AS total,
    min(start_time) AS first_start,
    max(end_time) AS last_end,
    true AS pending
  FROM pending_records, params
  WHERE record_type = 'StepsRecord'
    AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
  GROUP BY source_app
),
nutrition_protein_source_totals AS (
  SELECT
    source_app,
    sum(${numericSql("data->>'proteinGrams'")}) AS total
  FROM actual_records, params
  WHERE record_type = 'NutritionRecord'
    AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
  GROUP BY source_app
),
nutrition_energy_source_totals AS (
  SELECT
    source_app,
    sum(${numericSql("data->>'energyKcal'")}) AS total
  FROM actual_records, params
  WHERE record_type = 'NutritionRecord'
    AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
  GROUP BY source_app
),
max_actual_steps AS (
  SELECT max(total) AS total
  FROM step_source_totals
  WHERE pending = false
),
max_actual_protein AS (
  SELECT max(total) AS total
  FROM nutrition_protein_source_totals
),
max_actual_nutrition_energy AS (
  SELECT max(total) AS total
  FROM nutrition_energy_source_totals
),
active_burn_source_totals AS (
  SELECT
    source_app,
    sum(${numericSql("data->>'energyKcal'")}) AS total
  FROM actual_records, params
  WHERE record_type = 'ActiveCaloriesBurnedRecord'
    AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
  GROUP BY source_app
),
max_actual_active_burn AS (
  SELECT max(total) AS total
  FROM active_burn_source_totals
),
active_burn_logs AS (
  SELECT
    start_time,
    end_time,
    source_app,
    ${numericSql("data->>'energyKcal'")} AS kcal
  FROM actual_records, params
  WHERE record_type = 'ActiveCaloriesBurnedRecord'
    AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
    AND ${numericSql("data->>'energyKcal'")} IS NOT NULL
    AND source_app IN (
      SELECT source_app
      FROM active_burn_source_totals
      WHERE total = (SELECT total FROM max_actual_active_burn)
    )
),
workout_burn_logs AS (
  -- Per-workout burn (Hevy TotalCaloriesBurnedRecord) logged today. Titled from the matching
  -- ExerciseSessionRecord (same source + start_time) when present, so the line reads "Push day".
  SELECT
    tc.start_time,
    tc.source_app,
    ${numericSql("tc.data->>'energyKcal'")} AS kcal,
    (
      SELECT COALESCE(NULLIF(ex.data->>'title', ''), 'Workout')
      FROM actual_records ex
      WHERE ex.record_type = 'ExerciseSessionRecord'
        AND ex.source_app = tc.source_app
        AND ex.start_time = tc.start_time
      LIMIT 1
    ) AS title
  FROM actual_records tc, params
  WHERE tc.record_type = 'TotalCaloriesBurnedRecord'
    AND (tc.start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
    AND ${numericSql("tc.data->>'energyKcal'")} IS NOT NULL
),
training_logs AS (
  SELECT
    start_time,
    end_time,
    source_app,
    data
  FROM actual_records, params
  WHERE record_type = 'ExerciseSessionRecord'
    AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date BETWEEN params.today - 6 AND params.today
    AND (
      source_app = 'com.hevy'
      OR (source_app LIKE '%strava%' AND (data->>'exerciseType')::int IN (56, 57))
    )
)
SELECT
  COALESCE((
    SELECT jsonb_agg(jsonb_build_object('time', time, 'label', label, 'value', value) ORDER BY sort_key)
    FROM (
      SELECT
        start_time AS sort_key,
        to_char(start_time AT TIME ZONE '${USER_TIMEZONE}', 'HH24:MI') AS time,
        COALESCE(NULLIF(data->>'name', ''), 'Nutrition log') AS label,
        concat(
          round(${numericSql("data->>'proteinGrams'")}::numeric, 1)::text,
          'g',
          CASE
            WHEN ${numericSql("data->>'energyKcal'")} IS NULL THEN ''
            ELSE concat(' · ', round(${numericSql("data->>'energyKcal'")}::numeric)::text, ' kcal')
          END
        ) AS value
      FROM combined_records, params
      WHERE record_type = 'NutritionRecord'
        AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
        AND ${numericSql("data->>'proteinGrams'")} IS NOT NULL
        AND (
          source_app = '__pending_writes__'
          OR source_app IN (
            SELECT source_app
            FROM nutrition_protein_source_totals
            WHERE total = (SELECT total FROM max_actual_protein)
          )
        )
    ) protein_logs
  ), '[]'::jsonb) AS protein_items,
  COALESCE((
    SELECT jsonb_agg(jsonb_build_object('time', time, 'label', label, 'value', value) ORDER BY sort_key)
    FROM (
      SELECT
        start_time AS sort_key,
        to_char(start_time AT TIME ZONE '${USER_TIMEZONE}', 'HH24:MI') AS time,
        COALESCE(NULLIF(data->>'name', ''), 'Nutrition log') AS label,
        concat(
          round(${numericSql("data->>'energyKcal'")}::numeric)::text,
          ' kcal',
          CASE
            WHEN ${numericSql("data->>'proteinGrams'")} IS NULL THEN ''
            ELSE concat(' · ', round(${numericSql("data->>'proteinGrams'")}::numeric, 1)::text, 'g protein')
          END
        ) AS value
      FROM combined_records, params
      WHERE record_type = 'NutritionRecord'
        AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = params.today
        AND ${numericSql("data->>'energyKcal'")} IS NOT NULL
        AND (
          source_app = '__pending_writes__'
          OR source_app IN (
            SELECT source_app
            FROM nutrition_energy_source_totals
            WHERE total = (SELECT total FROM max_actual_nutrition_energy)
          )
        )
    ) intake_logs
  ), '[]'::jsonb) AS intake_items,
  COALESCE((
    SELECT jsonb_agg(jsonb_build_object('time', time, 'label', label, 'value', value) ORDER BY sort_key)
    FROM (
      SELECT
        first_start AS sort_key,
        CASE
          WHEN first_start IS NULL THEN NULL
          ELSE to_char(first_start AT TIME ZONE '${USER_TIMEZONE}', 'HH24:MI')
        END AS time,
        CASE
          WHEN source_app = 'com.ouraring.oura' THEN 'Oura steps'
          WHEN source_app LIKE 'com.android.healthconnect.phone%' THEN 'Phone steps'
          WHEN source_app = '__pending_writes__' THEN 'Pending steps'
          ELSE source_app
        END AS label,
        concat(
          to_char(round(COALESCE(total, 0)), 'FM999G999G999'),
          ' steps'
        ) AS value
      FROM step_source_totals
      WHERE COALESCE(total, 0) > 0
        AND (
          pending
          OR total = (SELECT total FROM max_actual_steps)
        )
    ) step_logs
  ), '[]'::jsonb) AS step_items,
  COALESCE((
    SELECT jsonb_agg(jsonb_build_object('time', time, 'label', label, 'value', value) ORDER BY sort_key)
    FROM (
      SELECT
        start_time AS sort_key,
        to_char(start_time AT TIME ZONE '${USER_TIMEZONE}', 'HH24:MI') AS time,
        concat(
          to_char(start_time AT TIME ZONE '${USER_TIMEZONE}', 'Mon DD'),
          ' · ',
          COALESCE(NULLIF(data->>'title', ''), CASE WHEN source_app LIKE '%strava%' THEN 'Run' ELSE 'Training session' END)
        ) AS label,
        concat(
          round(extract(epoch FROM (COALESCE(end_time, start_time) - start_time)) / 60.0)::text,
          ' min · ',
          CASE
            WHEN source_app = 'com.hevy' THEN 'Hevy'
            WHEN source_app LIKE '%strava%' THEN 'Strava'
            ELSE source_app
          END
        ) AS value
      FROM training_logs
    ) training_items
  ), '[]'::jsonb) AS training_items,
  COALESCE((
    SELECT jsonb_agg(jsonb_build_object('time', time, 'label', label, 'value', value) ORDER BY sort_key)
    FROM (
      SELECT
        start_time AS sort_key,
        to_char(start_time AT TIME ZONE '${USER_TIMEZONE}', 'HH24:MI') AS time,
        CASE
          WHEN source_app = 'com.ouraring.oura' THEN 'Oura active burn'
          ELSE source_app
        END AS label,
        concat(round(kcal)::text, ' kcal') AS value
      FROM active_burn_logs
    ) active_items
  ), '[]'::jsonb) AS active_burn_items,
  COALESCE((
    SELECT jsonb_agg(jsonb_build_object('time', time, 'label', label, 'value', value) ORDER BY sort_key)
    FROM (
      SELECT
        start_time AS sort_key,
        to_char(start_time AT TIME ZONE '${USER_TIMEZONE}', 'HH24:MI') AS time,
        title AS label,
        concat(round(kcal)::text, ' kcal') AS value
      FROM workout_burn_logs
    ) workout_items
  ), '[]'::jsonb) AS workout_burn_items
`;

/** Numeric columns come back from the pg driver as strings (or null); coerce, treating null as null. */
function num(v: unknown): number | null {
  if (v === null || v === undefined) return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

type StatusKey = "optimal" | "good" | "fair" | "attention";
type WeightDirection = "loss" | "gain" | "maintenance";

interface LeverLineItem {
  label: string;
  value: string;
  time?: string;
}

/** A supporting lever tile (protein / training / calorie balance). */
interface Lever {
  key: string;
  title: string;
  score: number; // 0..100, drives the tile's progress fill
  label: string; // short status word
  status: StatusKey;
  value: number;
  goal: number | null; // null when a lever has no fixed target
  unit?: string;
  period: "day" | "week"; // "week" tiles read "this week"
  detail: string; // e.g. "142 / 150 g"
  action: string; // concise recovery/action copy kept for API consumers outside the Home card
  forecastValue?: number;
  forecastScore?: number;
  forecastLabel?: string;
  forecastStatus?: StatusKey;
  forecastDetail?: string;
  lineItems: LeverLineItem[];
}

function lineItemsFrom(value: unknown): LeverLineItem[] {
  let raw: unknown = [];
  if (Array.isArray(value)) {
    raw = value;
  } else if (typeof value === "string") {
    try {
      raw = JSON.parse(value) as unknown;
    } catch {
      raw = [];
    }
  }
  if (!Array.isArray(raw)) return [];
  return raw
    .map((item) => {
      if (item === null || typeof item !== "object") return null;
      const row = item as Record<string, unknown>;
      const label = typeof row.label === "string" ? row.label : null;
      const itemValue = typeof row.value === "string" ? row.value : null;
      const time = typeof row.time === "string" ? row.time : undefined;
      if (label === null || itemValue === null) return null;
      const result: LeverLineItem = { label, value: itemValue };
      if (time !== undefined) result.time = time;
      return result;
    })
    .filter((item): item is LeverLineItem => item !== null);
}

function signedLineItems(items: LeverLineItem[], prefix: string, sign: "+" | "-"): LeverLineItem[] {
  return items.map((item) => ({
    ...item,
    label: `${prefix}${item.label}`,
    value: item.value.startsWith("+") || item.value.startsWith("-") ? item.value : `${sign}${item.value}`,
  }));
}

/** The bodyweight hero — the recomp scoreboard. */
interface WeightHero {
  score: number;
  label: string;
  status: StatusKey;
  current: number | null; // trailing 7-day average kg
  unit: "kg";
  target: number | null;
  toGo: number | null; // kg remaining to target (>0 while outside target range)
  changePerWeek: number | null; // fitted slope, kg/week (negative = losing)
  bodyFatPct: number | null;
  series: { date: string; value: number }[]; // oldest → newest, for the sparkline
  detail: string;
  action: string;
}

interface EnergyBalanceTarget {
  dailyTarget: number | null; // signed kcal/day: negative = deficit, positive = surplus
  direction: WeightDirection | null;
  basisWeightKg: number | null;
}

interface ScorePoint {
  date: string;
  value: number;
}

interface RecoveryMetric {
  key: "readiness" | "sleep" | "stress";
  title: string;
  score: number;
  label: string;
  status: StatusKey;
  value: number;
  unit: string;
  series: ScorePoint[];
  detail: string;
}

interface RecoveryScore {
  score: number;
  label: string;
  status: StatusKey;
  series: ScorePoint[];
  metrics: RecoveryMetric[];
  detail: string;
}

function scoreFromGoal(value: number, goal: number): number {
  if (goal <= 0) return 0;
  return Math.max(0, Math.min(100, Math.round((value / goal) * 100)));
}

function clampScore(value: number): number {
  return Math.max(0, Math.min(100, Math.round(value)));
}

function avg(values: (number | null)[]): number | null {
  const present = values.filter((v): v is number => v !== null && Number.isFinite(v));
  if (present.length === 0) return null;
  return present.reduce((s, v) => s + v, 0) / present.length;
}

function weightedScore(items: { score: number | null; weight: number }[]): number {
  const present = items.filter((i): i is { score: number; weight: number } => i.score !== null);
  if (present.length === 0) return 0;
  const totalWeight = present.reduce((s, i) => s + i.weight, 0);
  return clampScore(present.reduce((s, i) => s + i.score * i.weight, 0) / totalWeight);
}

function statusFromScore(score: number): StatusKey {
  if (score >= 85) return "optimal";
  if (score >= 70) return "good";
  if (score >= 55) return "fair";
  return "attention";
}

/**
 * The active window we pace cumulative daily metrics against. Protein accumulates through the
 * waking day, so at 9am nobody has hit their daily target yet — judging it against the *full* goal
 * would flag every morning as "behind" (red). Instead we pace them against how far into this window
 * we are right now, so an empty tile in the morning reads "on track".
 */
const ACTIVE_START_MIN = 6 * 60; // 06:00
const ACTIVE_END_MIN = 22 * 60; // 22:00

/** Current wall-clock date + minutes-past-midnight in the user's timezone (no server-tz reliance). */
function tzNow(tz: string): { date: string; minutes: number } {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: tz,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).formatToParts(new Date());
  const get = (t: string) => parts.find((p) => p.type === t)?.value ?? "0";
  const date = `${get("year")}-${get("month")}-${get("day")}`;
  const hour = Number(get("hour")) % 24; // Intl can emit "24" for midnight; fold to 0.
  return { date, minutes: hour * 60 + Number(get("minute")) };
}

/** 0..1 fraction of the active window elapsed. Always 1 for a completed (past) day. */
function dayProgressFraction(isToday: boolean, nowMinutes: number): number {
  if (!isToday) return 1;
  const span = ACTIVE_END_MIN - ACTIVE_START_MIN;
  return Math.max(0, Math.min(1, (nowMinutes - ACTIVE_START_MIN) / span));
}

/**
 * Score relative to where you *should* be by now (value vs the pro-rated goal). 100 = on or ahead
 * of pace. Before the active window starts nothing is expected yet, so we return 100 rather than 0.
 */
function paceScore(value: number, goal: number, dayFrac: number): number {
  if (goal <= 0) return 0;
  const paced = goal * dayFrac;
  if (paced <= 0) return 100;
  return Math.max(0, Math.min(100, Math.round((value / paced) * 100)));
}

function paceStatus(raw: number, pace: number): { label: string; status: StatusKey } {
  // Green (optimal) is reserved for actually succeeding: goal met, or genuinely on track to hit
  // the *full* goal — near it (raw) AND on/ahead of the prorated line (pace). Being merely ahead
  // of the time-of-day pace while far from the goal (e.g. 80g of 140g at noon) is NOT green; it's
  // an in-progress (blue "good") state. Below that we fall to yellow (a bit behind) / red (behind).
  if (raw >= 100) return { label: "Goal met", status: "optimal" };
  if (raw >= 85 && pace >= 85) return { label: "On track", status: "optimal" };
  if (pace >= 70) return { label: "In progress", status: "good" };
  if (pace >= 55) return { label: "A bit behind", status: "fair" };
  return { label: "Behind", status: "attention" };
}

function goalDirection(weightKg: number | null, targetKg: number | null): WeightDirection | null {
  if (weightKg === null) return null;
  if (targetKg === null) return "loss";
  if (weightKg > targetKg + TARGET_WEIGHT_TOLERANCE_KG) return "loss";
  if (weightKg < targetKg - TARGET_WEIGHT_TOLERANCE_KG) return "gain";
  return "maintenance";
}

function objectiveFor(direction: WeightDirection | null): string {
  if (direction === "gain") return "Weight gain";
  if (direction === "maintenance") return "Maintenance";
  return "Fat loss";
}

function formatKg(v: number): string {
  return `${Math.round(v * 10) / 10} kg`;
}

function formatKcal(value: number): string {
  return `${Math.round(value).toLocaleString("en-US")} kcal`;
}

function formatSignedKcal(value: number): string {
  const rounded = Math.round(value);
  if (rounded === 0) return "0 kcal";
  return `${rounded < 0 ? "−" : "+"}${Math.abs(rounded).toLocaleString("en-US")} kcal`;
}

function calorieTargetFromWeight(latestWeightKg: number | null, goals: Goals): EnergyBalanceTarget {
  const direction = goalDirection(latestWeightKg, goals.weightTargetKg);
  if (latestWeightKg === null || direction === null) {
    return { dailyTarget: null, direction: null, basisWeightKg: null };
  }
  if (direction === "maintenance") {
    return { dailyTarget: 0, direction, basisWeightKg: latestWeightKg };
  }
  const magnitude = (latestWeightKg * BODY_WEIGHT_CHANGE_FRACTION_PER_WEEK * KCAL_PER_KG_BODY_WEIGHT) / 7;
  const signed = direction === "loss" ? -magnitude : magnitude;
  return {
    dailyTarget: Math.round(signed / 10) * 10,
    direction,
    basisWeightKg: Math.round(latestWeightKg * 10) / 10,
  };
}

function formatSleep(minutes: number): string {
  const rounded = Math.max(0, Math.round(minutes));
  const h = Math.floor(rounded / 60);
  const m = rounded % 60;
  return h > 0 ? `${h}h ${m.toString().padStart(2, "0")}m` : `${m}m`;
}

function sleepScore(minutes: number, targetMinutes: number): number {
  if (minutes <= 0) return 0;
  const target = Math.max(420, targetMinutes);
  const under = Math.min(1, minutes / target);
  const overPenalty = minutes > target * 1.15 ? Math.min(35, (minutes - target * 1.15) / 6) : 0;
  return clampScore(under * 100 - overPenalty);
}

function hrvScore(hrv: number | null, baseline: number | null): number | null {
  if (hrv === null || baseline === null || baseline <= 0) return null;
  return clampScore(78 + (hrv / baseline - 1) * 160);
}

function rhrScore(rhr: number | null, baseline: number | null): number | null {
  if (rhr === null || baseline === null || baseline <= 0) return null;
  return clampScore(78 - (rhr / baseline - 1) * 220);
}

function activityBalanceScore(activeEnergy: number, baseline: number | null): number {
  if (baseline === null || baseline <= 0) return activeEnergy > 0 ? 72 : 76;
  const ratio = activeEnergy / baseline;
  if (ratio <= 0.65) return 88;
  if (ratio <= 1.15) return 82;
  return clampScore(82 - (ratio - 1.15) * 80);
}

function metricLabel(key: RecoveryMetric["key"], score: number): string {
  if (key === "readiness") {
    if (score >= 85) return "Ready";
    if (score >= 70) return "Recovered";
    if (score >= 55) return "Mixed";
    return "Low";
  }
  if (key === "sleep") {
    if (score >= 85) return "Rested";
    if (score >= 70) return "Enough";
    if (score >= 55) return "Short";
    return "Low";
  }
  if (score >= 85) return "Calm";
  if (score >= 70) return "Balanced";
  if (score >= 55) return "Loaded";
  return "High";
}

function recoveryLabel(score: number): string {
  if (score >= 85) return "Primed";
  if (score >= 70) return "Steady";
  if (score >= 55) return "Mixed";
  return "Run down";
}

function buildRecoveryScore(rows: Record<string, unknown>[], goals: Goals): RecoveryScore {
  const days = rows.map((r) => ({
    date: String(r.date),
    sleepMinutes: num(r.sleep_minutes) ?? 0,
    activeEnergy: num(r.active_energy) ?? 0,
    hrv: num(r.hrv_ms),
    rhr: num(r.rhr_bpm),
  }));
  const prior = days.slice(0, -1);
  const sleepBaseline = avg(prior.map((d) => (d.sleepMinutes > 0 ? d.sleepMinutes : null)));
  const hrvBaseline = avg(prior.map((d) => d.hrv));
  const rhrBaseline = avg(prior.map((d) => d.rhr));
  const activeBaseline = avg(prior.map((d) => (d.activeEnergy > 0 ? d.activeEnergy : null)));

  const scored = days.map((day) => {
    const sleep = sleepScore(day.sleepMinutes, goals.sleepMinutesTarget);
    const hrv = hrvScore(day.hrv, hrvBaseline);
    const rhr = rhrScore(day.rhr, rhrBaseline);
    const sleepConsistency =
      sleepBaseline !== null && day.sleepMinutes > 0
        ? clampScore(100 - Math.abs(day.sleepMinutes - sleepBaseline) / 3)
        : null;
    const readiness = weightedScore([
      { score: sleep, weight: 0.4 },
      { score: hrv, weight: 0.35 },
      { score: rhr, weight: 0.2 },
      { score: sleepConsistency, weight: 0.05 },
    ]);
    const stress = weightedScore([
      { score: activityBalanceScore(day.activeEnergy, activeBaseline), weight: 0.4 },
      { score: sleep, weight: 0.25 },
      { score: hrv, weight: 0.2 },
      { score: rhr, weight: 0.15 },
    ]);
    const recovery = clampScore((readiness + sleep + stress) / 3);
    return { ...day, sleep, readiness, stress, recovery };
  });

  const current = scored.at(-1);
  if (!current) {
    return {
      score: 0,
      label: "No data",
      status: "attention",
      series: [],
      metrics: [],
      detail: "Sync sleep and vitals to build a recovery trend.",
    };
  }

  const point = (key: "recovery" | RecoveryMetric["key"]) =>
    scored.map((d) => ({
      date: d.date,
      value: key === "recovery" ? d.recovery : key === "readiness" ? d.readiness : key === "sleep" ? d.sleep : d.stress,
    }));
  const metric = (
    key: RecoveryMetric["key"],
    title: string,
    score: number,
    value: number,
    unit: string,
    detail: string,
  ): RecoveryMetric => ({
    key,
    title,
    score,
    label: metricLabel(key, score),
    status: statusFromScore(score),
    value,
    unit,
    series: point(key),
    detail,
  });

  const recoveryScore = current.recovery;
  return {
    score: recoveryScore,
    label: recoveryLabel(recoveryScore),
    status: statusFromScore(recoveryScore),
    series: point("recovery"),
    metrics: [
      metric(
        "readiness",
        "Readiness",
        current.readiness,
        current.readiness,
        "score",
        current.hrv !== null || current.rhr !== null ? "Sleep, HRV, and resting HR vs baseline" : "Sleep-based estimate",
      ),
      metric("sleep", "Sleep", current.sleep, current.sleepMinutes / 60, "h", formatSleep(current.sleepMinutes)),
      metric("stress", "Stress", current.stress, current.stress, "score", "Activity load, sleep, and recovery balance"),
    ],
    detail: "Readiness, sleep, and stress balance over 14 days.",
  };
}

/**
 * Least-squares slope of weight over day-offsets, returned in kg per week. Needs ≥2 distinct days;
 * otherwise null (no trend yet). Points are {offsetDays, kg} where offset is days from the oldest.
 */
function weeklySlope(points: { offset: number; kg: number }[]): number | null {
  if (points.length < 2) return null;
  const n = points.length;
  const meanX = points.reduce((s, p) => s + p.offset, 0) / n;
  const meanY = points.reduce((s, p) => s + p.kg, 0) / n;
  let num2 = 0;
  let den = 0;
  for (const p of points) {
    num2 += (p.offset - meanX) * (p.kg - meanY);
    den += (p.offset - meanX) ** 2;
  }
  if (den === 0) return null;
  return (num2 / den) * 7; // per-day slope → per-week
}

/**
 * Score the bodyweight trend against the target direction. Loss mode rewards a controlled downward
 * trend; gain mode rewards a controlled upward trend; maintenance mode rewards staying near target.
 */
function buildWeightHero(
  series: { date: string; value: number }[],
  goals: Goals,
  bodyFatPct: number | null,
): WeightHero {
  const base: Omit<WeightHero, "score" | "label" | "status" | "detail" | "action"> = {
    current: null,
    unit: "kg",
    target: goals.weightTargetKg,
    toGo: null,
    changePerWeek: null,
    bodyFatPct,
    series,
  };

  if (series.length === 0) {
    return {
      ...base,
      score: 0,
      label: "No data",
      status: "attention",
      detail: "Log a weigh-in to start tracking",
      action: "Ask Claude to log today's weight to start your trend.",
    };
  }

  // series is oldest → newest. current = trailing up-to-7-day average of the most recent points.
  const recent = series.slice(-7);
  const current = recent.reduce((s, p) => s + p.value, 0) / recent.length;

  // Slope over the whole window, using day offsets from the earliest point (gaps handled naturally).
  const epoch = (d: string) => Date.parse(`${d}T00:00:00Z`) / 86_400_000;
  const day0 = epoch(series[0]!.date); // series is non-empty here (handled above)
  const slope = weeklySlope(series.map((p) => ({ offset: epoch(p.date) - day0, kg: p.value })));

  const target = goals.weightTargetKg;
  const direction = goalDirection(current, target);
  const toGo =
    target !== null && direction !== "maintenance" ? Math.round(Math.abs(current - target) * 10) / 10 : null;
  const changePerWeek = slope === null ? null : Math.round(slope * 100) / 100;

  const trendText =
    changePerWeek === null
      ? "trend forming"
      : `${changePerWeek <= 0 ? "−" : "+"}${Math.abs(changePerWeek).toFixed(1)} kg/wk`;
  const goalText = toGo !== null && toGo > 0 ? ` · ${toGo.toFixed(1)} kg to goal` : "";
  const detail = `${formatKg(current)} · ${trendText}${goalText}`;

  let score: number;
  let label: string;
  let status: StatusKey;
  let action: string;

  if (direction === "maintenance") {
    score = 100;
    label = "At goal";
    status = "optimal";
    action = "You're at your target weight. Hold maintenance with protein and training.";
  } else if (changePerWeek === null) {
    score = 60;
    label = "Trend forming";
    status = "fair";
    action = "A couple more weigh-ins and your trend line will be reliable.";
  } else if (direction === "gain") {
    if (changePerWeek >= 0.5) {
      score = 72;
      label = "Gaining fast";
      status = "fair";
      action = "Weight is rising quickly. Keep the surplus controlled and training consistent.";
    } else if (changePerWeek >= 0.1) {
      score = 100;
      label = "On track";
      status = "optimal";
      action = "Weight gain is trending at a controlled pace. Hold this rhythm.";
    } else if (changePerWeek >= -0.1) {
      score = 62;
      label = "Stalled";
      status = "fair";
      action = "Weight is flat. A small calorie surplus can restart the gain.";
    } else {
      score = 34;
      label = "Trending down";
      status = "attention";
      action = "Weight is drifting away from target. Increase intake and keep training consistent.";
    }
  } else {
    if (changePerWeek <= -0.75) {
      score = 72;
      label = "Losing fast";
      status = "fair";
      action = "Dropping fast can cost muscle. Ease the deficit and keep protein high.";
    } else if (changePerWeek <= -0.1) {
      score = 100;
      label = "On track";
      status = "optimal";
      action = "Fat loss is trending at a healthy pace. Hold this rhythm.";
    } else if (changePerWeek <= 0.1) {
      score = 62;
      label = "Stalled";
      status = "fair";
      action = "Weight is flat. A small calorie trim can restart the loss.";
    } else {
      score = 34;
      label = "Trending up";
      status = "attention";
      action = "Weight is drifting up. Tighten intake and protein to get back on track.";
    }
  }

  return { ...base, current: Math.round(current * 10) / 10, target, toGo, changePerWeek, score, label, status, detail, action };
}

/** A daily "at-least" lever (protein, steps), paced to the time of day. */
function dailyLever(
  key: string,
  title: string,
  value: number,
  goal: number,
  unit: string | undefined,
  dayFrac: number,
  formatUnit: (n: number) => string,
): Lever {
  const score = scoreFromGoal(value, goal);
  const pace = paceScore(value, goal, dayFrac);
  const { label, status } = paceStatus(score, pace);
  return {
    key,
    title,
    score,
    label,
    status,
    value,
    goal,
    unit,
    period: "day",
    detail: `${formatUnit(value)} / ${formatUnit(goal)}`,
    action: actionFor(title, pace, score),
    lineItems: [],
  };
}

function energyBalanceScoreFor(value: number, target: EnergyBalanceTarget): number {
  if (target.dailyTarget === null) return 0;
  if (target.direction === "loss") {
    const targetDeficit = Math.max(1, Math.abs(target.dailyTarget));
    return clampScore((Math.max(0, -value) / targetDeficit) * 100);
  }
  if (target.direction === "gain") {
    const targetSurplus = Math.max(1, Math.abs(target.dailyTarget));
    return clampScore((Math.max(0, value) / targetSurplus) * 100);
  }
  const tolerance = 125;
  return clampScore(100 - (Math.abs(value - target.dailyTarget) / tolerance) * 30);
}

function energyBalanceLabelFor(value: number, target: EnergyBalanceTarget, score: number): string {
  if (target.dailyTarget === null) return "Need weight";
  if (target.direction === "loss") {
    return value <= target.dailyTarget ? "Deficit met" : score >= 70 ? "Near target" : "Deficit short";
  }
  if (target.direction === "gain") {
    return value >= target.dailyTarget ? "Surplus met" : score >= 70 ? "Near target" : "Surplus short";
  }
  return Math.abs(value - target.dailyTarget) <= 125 ? "Balanced" : value < 0 ? "Deficit" : "Surplus";
}

function energyBalanceLever(
  value: number,
  target: EnergyBalanceTarget,
  intakeEnergy: number,
  burnedEnergy: number,
  forecastRemainingIntakeEnergy: number,
  forecastSampleDays: number,
  plannedFutureEnergy: number,
  plannedBucketCount: number,
): Lever {
  const roundedValue = Math.round(value);
  const title = "Calorie balance";
  const forecastIntakeEnergy = intakeEnergy + Math.max(0, forecastRemainingIntakeEnergy);
  const forecastValue = Math.round(forecastIntakeEnergy - burnedEnergy);

  if (target.dailyTarget === null) {
    return {
      key: "energy_balance",
      title,
      score: 0,
      label: "Need weight",
      status: "attention",
      value: roundedValue,
      goal: null,
      unit: "kcal",
      period: "day",
      detail: `${formatSignedKcal(roundedValue)} today`,
      action: "Log weight so the calorie target can be derived from body weight.",
      lineItems: [],
    };
  }

  if (burnedEnergy <= 0) {
    return {
      key: "energy_balance",
      title,
      score: 0,
      label: "Need burn",
      status: "attention",
      value: roundedValue,
      goal: target.dailyTarget,
      unit: "kcal",
      period: "day",
      detail: `${formatSignedKcal(roundedValue)} today`,
      action: "Sync active calories and BMR inputs to calculate today's balance.",
      lineItems: [],
    };
  }

  const score = energyBalanceScoreFor(roundedValue, target);
  const status = statusFromScore(score);
  const label = energyBalanceLabelFor(roundedValue, target, score);
  const forecastScore = energyBalanceScoreFor(forecastValue, target);
  const forecastStatus = statusFromScore(forecastScore);
  const forecastLabel = energyBalanceLabelFor(forecastValue, target, forecastScore);

  const targetText = formatSignedKcal(target.dailyTarget);
  const forecastText = formatSignedKcal(forecastValue);
  const plannedText =
    plannedFutureEnergy > 0
      ? `current intake already includes planned future food (+${formatKcal(plannedFutureEnergy)})`
      : "current intake";
  const trendText =
    forecastSampleDays > 0 && forecastRemainingIntakeEnergy > 0
      ? `from ${plannedText} plus recent unmatched food after this time (+${formatKcal(forecastRemainingIntakeEnergy)})`
      : plannedBucketCount > 0
        ? `from ${plannedText}; matching future meal window already logged`
        : "from current intake; no recent food after this time";
  const intakeNote = intakeEnergy <= 0 ? " Log food to make this reliable." : "";

  return {
    key: "energy_balance",
    title,
    score,
    label,
    status,
    value: roundedValue,
    goal: target.dailyTarget,
    unit: "kcal",
    period: "day",
    detail: `${formatSignedKcal(roundedValue)} / target ${targetText}`,
    action: `Forecast ${forecastText} ${trendText}; target ${targetText}.${intakeNote}`,
    forecastValue,
    forecastScore,
    forecastLabel,
    forecastStatus,
    forecastDetail: `${forecastText} likely by end of day`,
    lineItems: [],
  };
}

function actionFor(title: string, pace: number, raw: number): string {
  if (raw >= 100) return `${title} goal met. Keep it steady.`;
  if (pace >= 85) return `${title} is on track. Stay steady.`;
  if (pace >= 55) return `${title} is close. A small push helps.`;
  return `${title} is behind today.`;
}

export async function GET(request: NextRequest) {
  if (!isDeviceAuthorized(request)) {
    return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  }

  const now = tzNow(USER_TIMEZONE);
  const requested = request.nextUrl.searchParams.get("date");
  const targetDate =
    requested && /^\d{4}-\d{2}-\d{2}$/.test(requested) && requested <= now.date ? requested : now.date;
  const isToday = targetDate === now.date;
  const dayFrac = dayProgressFraction(isToday, now.minutes);

  let row: Record<string, unknown>;
  let weightRows: Record<string, unknown>[] = [];
  let recoveryRows: Record<string, unknown>[] = [];
  let detailRow: Record<string, unknown> = {};
  let remainingIntakeRows: Record<string, unknown>[] = [];
  try {
    const [rows, weights, recoveryHistory, detailRows, remainingRows] = await Promise.all([
      runReadOnlyQuery(homeQuery(targetDate)),
      runReadOnlyQuery(weightSeriesQuery(targetDate)),
      runReadOnlyQuery(recoverySeriesQuery(targetDate)),
      runReadOnlyQuery(homeDetailRowsQuery(targetDate)),
      isToday ? runReadOnlyQuery(remainingIntakeTrendQuery(targetDate, now.minutes)) : Promise.resolve([]),
    ]);
    row = rows[0] ?? {};
    weightRows = weights;
    recoveryRows = recoveryHistory;
    detailRow = detailRows[0] ?? {};
    remainingIntakeRows = remainingRows;
  } catch (e) {
    return NextResponse.json({ error: `query failed: ${(e as Error).message}` }, { status: 500 });
  }

  const [goals, profile] = await Promise.all([getGoals(), getUserProfile()]);

  const steps = Math.round(num(row.steps_today) ?? 0);
  const protein = Math.round(num(row.protein_today) ?? 0);
  const trainingSessions = Math.round(num(row.training_week) ?? 0);
  const bodyFat = num(row.body_fat);
  const latestWeight = num(row.latest_weight);
  const intakeEnergy = Math.round(num(row.nutrition_energy_today) ?? 0);
  const activeEnergy = Math.round(num(row.active_energy_today) ?? 0);
  // Hevy workout burn (TotalCaloriesBurnedRecord, scoped per session) — strength training that
  // wearable active-calorie feeds under-count. Added on top of active burn, not de-duped against it.
  const workoutEnergy = Math.round(num(row.workout_energy_today) ?? 0);
  const latestBmr = deriveBmrKcalPerDay(latestWeight, bodyFat, targetDate, profile, num(row.latest_bmr)).kcalPerDay;
  const estimatedBasalBurn = latestBmr ?? 0;
  const burnedEnergy = Math.round(activeEnergy + workoutEnergy + estimatedBasalBurn);
  const forecastRemainingIntakeEnergy = isToday
    ? Math.round(num(remainingIntakeRows[0]?.remaining_energy) ?? 0)
    : 0;
  const forecastSampleDays = isToday ? Math.round(num(remainingIntakeRows[0]?.sample_days) ?? 0) : 0;
  const plannedFutureEnergy = isToday ? Math.round(num(remainingIntakeRows[0]?.planned_future_energy) ?? 0) : 0;
  const plannedBucketCount = isToday ? Math.round(num(remainingIntakeRows[0]?.planned_bucket_count) ?? 0) : 0;

  // Weight series arrives newest → oldest; flip to oldest → newest for the sparkline + fit.
  const series = weightRows
    .map((r) => ({ date: String(r.day), value: num(r.kg) }))
    .filter((p): p is { date: string; value: number } => p.value !== null)
    .reverse();

  const weight = buildWeightHero(series, goals, bodyFat);
  const recovery = buildRecoveryScore(recoveryRows, goals);
  const proteinItems = lineItemsFrom(detailRow.protein_items);
  const intakeItems = lineItemsFrom(detailRow.intake_items);
  const stepItems = lineItemsFrom(detailRow.step_items);
  const trainingItems = lineItemsFrom(detailRow.training_items);
  const activeBurnItems = lineItemsFrom(detailRow.active_burn_items);
  const workoutBurnItems = lineItemsFrom(detailRow.workout_burn_items);

  // --- Levers ---------------------------------------------------------------
  const protein_lever: Lever = {
    ...dailyLever("protein", "Protein", protein, goals.proteinGramsTarget, "g", dayFrac, (n) => `${Math.round(n)}g`),
    lineItems: proteinItems,
  };
  const steps_lever: Lever = {
    ...dailyLever("steps", "Steps", steps, goals.stepsTarget, undefined, dayFrac, (n) =>
      Math.round(n).toLocaleString("en-US"),
    ),
    lineItems: stepItems,
  };
  // Training: a weekly count, always judged against the full week (no intra-week pacing).
  const trainScore = scoreFromGoal(trainingSessions, goals.weeklyWorkoutTarget);
  const trainStatus: { label: string; status: StatusKey } =
    trainScore >= 100
      ? { label: "Goal met", status: "optimal" }
      : trainScore >= 75
        ? { label: "Almost", status: "good" }
        : trainScore >= 50
          ? { label: "Halfway", status: "fair" }
          : { label: "Behind", status: "attention" };
  const training_lever: Lever = {
    key: "training",
    title: "Training",
    score: trainScore,
    label: trainStatus.label,
    status: trainStatus.status,
    value: trainingSessions,
    goal: goals.weeklyWorkoutTarget,
    unit: "sessions",
    period: "week",
    detail: `${trainingSessions} / ${goals.weeklyWorkoutTarget} this week`,
    action:
      trainScore >= 100
        ? "Weekly training target hit. Muscle-retention signal is strong."
        : "Get a lifting session in — it's what makes the calorie target work for body composition.",
    lineItems: [
      ...(trainingItems.length > 0 ? trainingItems : [{ label: "Qualifying sessions", value: "None logged" }]),
    ],
  };

  // Calorie balance: intake - estimated daily expenditure (active burn + per-session workout burn
  // + a full-day basal burn). Wearable active-calorie feeds under-count strength training, so Hevy's
  // per-workout TotalCaloriesBurnedRecord is folded in on top rather than treated as a full-day feed.
  // The displayed balance and status are actual so far. The action copy forecasts the final balance
  // by adding the recent trend for food logged after the current clock time.
  // The target is derived from the latest bodyweight at 0.5% body weight per week, with sign chosen
  // by goal weight direction (deficit / surplus / maintenance).
  const balanceTarget = calorieTargetFromWeight(latestWeight, goals);
  const energy_lever: Lever = {
    ...energyBalanceLever(
      intakeEnergy - burnedEnergy,
      balanceTarget,
      intakeEnergy,
      burnedEnergy,
      forecastRemainingIntakeEnergy,
      forecastSampleDays,
      plannedFutureEnergy,
      plannedBucketCount,
    ),
    lineItems: [
      ...signedLineItems(intakeItems, "Food · ", "+"),
      ...signedLineItems(activeBurnItems, "Burn · ", "-"),
      ...signedLineItems(workoutBurnItems, "Workout · ", "-"),
      { label: "Basal estimate", value: formatSignedKcal(-estimatedBasalBurn) },
    ],
  };

  const levers: Lever[] = [energy_lever, protein_lever, training_lever, steps_lever];

  // Overall "recomp" score: the weight trend is the objective, so it carries the most weight; the
  // levers are how you steer it. 55% weight / 45% split across the four levers.
  const leverAvg = levers.reduce((s, l) => s + l.score, 0) / levers.length;
  const overallScore = Math.round(weight.score * 0.55 + leverAvg * 0.45);
  const overall = { score: overallScore, ...paceStatus(overallScore, overallScore) };

  const insights = buildInsights(goals, weight, levers, isToday);

  return NextResponse.json({
    date: (row.date as string) ?? targetDate,
    isToday,
    objective: objectiveFor(balanceTarget.direction),
    overall,
    recovery,
    weight,
    levers,
    insights,
  });
}

/**
 * Deterministic, rule-based insight strings. Recomp-relevant: protein gap (muscle retention),
 * weight trend momentum, and training gap. Every rule guards against null/missing
 * data (skip, never emit NaN). Capped at 4. Nothing here calls an LLM.
 */
function buildInsights(
  goals: Goals,
  weight: WeightHero,
  levers: Lever[],
  isToday: boolean,
): string[] {
  const insights: string[] = [];
  const fmt = (n: number) => Math.round(n).toLocaleString("en-US");
  const protein = levers.find((l) => l.key === "protein")!;
  const training = levers.find((l) => l.key === "training")!;

  // 1. Weight trend headline (the objective).
  if (weight.changePerWeek !== null && weight.current !== null) {
    const direction = goalDirection(weight.current, goals.weightTargetKg);
    if (direction === "gain") {
      if (weight.changePerWeek >= 0.1 && weight.changePerWeek < 0.5) {
        insights.push(`On pace — gaining ${weight.changePerWeek.toFixed(1)} kg/wk${weight.toGo && weight.toGo > 0 ? `, ${weight.toGo.toFixed(1)} kg to goal` : ""}`);
      } else if (weight.changePerWeek >= 0.5) {
        insights.push(`Weight up ${weight.changePerWeek.toFixed(1)} kg/wk — keep the surplus controlled`);
      } else if (weight.changePerWeek < -0.1) {
        insights.push(`Weight down ${Math.abs(weight.changePerWeek).toFixed(1)} kg/wk — add a small surplus`);
      } else {
        insights.push(`Weight has stalled — add ~200 kcal to restart the gain`);
      }
    } else if (direction === "maintenance") {
      if (Math.abs(weight.changePerWeek) > 0.1) {
        insights.push(`Weight drifting ${weight.changePerWeek > 0 ? "up" : "down"} ${Math.abs(weight.changePerWeek).toFixed(1)} kg/wk — bring balance toward maintenance`);
      }
    } else {
      if (weight.changePerWeek <= -0.1 && weight.changePerWeek > -0.75) {
        insights.push(`On pace — losing ${Math.abs(weight.changePerWeek).toFixed(1)} kg/wk${weight.toGo && weight.toGo > 0 ? `, ${weight.toGo.toFixed(1)} kg to goal` : ""}`);
      } else if (weight.changePerWeek > 0.1) {
        insights.push(`Weight up ${weight.changePerWeek.toFixed(1)} kg/wk — tighten intake to resume fat loss`);
      } else if (weight.changePerWeek > -0.1) {
        insights.push(`Weight has stalled — trim ~200 kcal or add a daily walk`);
      }
    }
  }

  // 2. Protein gap — the top lever for holding lean mass while weight changes.
  if (isToday && protein.value < goals.proteinGramsTarget) {
    insights.push(`${fmt(goals.proteinGramsTarget - protein.value)}g protein to go — key to holding muscle`);
  } else if (protein.value >= goals.proteinGramsTarget) {
    insights.push(`Protein goal hit — ${fmt(protein.value)}g`);
  }

  // 3. Training gap for the week.
  if (training.value < goals.weeklyWorkoutTarget) {
    const left = goals.weeklyWorkoutTarget - training.value;
    insights.push(`${left} more ${left === 1 ? "session" : "sessions"} to hit ${goals.weeklyWorkoutTarget} this week`);
  }

  return insights.slice(0, 4);
}
