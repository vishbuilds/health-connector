import { Client } from "pg";

const url = process.env.DATABASE_URL;

if (!url) {
  throw new Error("DATABASE_URL is not set");
}

const client = new Client({ connectionString: url });

await client.connect();
try {
  await client.query(`
    CREATE TABLE IF NOT EXISTS user_goals (
      id text PRIMARY KEY DEFAULT 'default'
    );

    ALTER TABLE user_goals
      ADD COLUMN IF NOT EXISTS steps_target integer NOT NULL DEFAULT 10000,
      ADD COLUMN IF NOT EXISTS sleep_minutes_target integer NOT NULL DEFAULT 480,
      ADD COLUMN IF NOT EXISTS hydration_liters_target numeric NOT NULL DEFAULT 2.5,
      ADD COLUMN IF NOT EXISTS active_calories_target integer NOT NULL DEFAULT 500, -- legacy; no longer used by Home
      ADD COLUMN IF NOT EXISTS protein_grams_target integer NOT NULL DEFAULT 140,
      ADD COLUMN IF NOT EXISTS weekly_workout_target integer NOT NULL DEFAULT 4,
      ADD COLUMN IF NOT EXISTS weight_target_kg numeric,
      ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
  `);
  console.log("Ensured user_goals table exists.");
} finally {
  await client.end();
}
