import fs from "fs";
import { Pool } from "pg";

function loadEnv(path: string) {
  if (!fs.existsSync(path)) return;
  for (const line of fs.readFileSync(path, "utf8").split(/\n/)) {
    const match = line.match(/^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/);
    if (!match) continue;
    let value = match[2]!.trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    if (!process.env[match[1]!]) process.env[match[1]!] = value;
  }
}

function databaseUrl(): string {
  if (process.env.DATABASE_URL) return process.env.DATABASE_URL;
  const host = process.env.POSTGRES_HOST;
  const user = process.env.POSTGRES_USER;
  const password = process.env.POSTGRES_PASSWORD;
  const database = process.env.POSTGRES_DATABASE;
  if (!host || !user || !password || !database) {
    throw new Error("DATABASE_URL or POSTGRES_HOST/POSTGRES_USER/POSTGRES_PASSWORD/POSTGRES_DATABASE is required");
  }
  return `postgres://${encodeURIComponent(user)}:${encodeURIComponent(password)}@${host}:5432/${encodeURIComponent(database)}`;
}

loadEnv(".env.local");
loadEnv(".context/.env.production.local");
loadEnv(".context/vercel-production-pulled.env");

const pool = new Pool({
  connectionString: databaseUrl(),
  ssl: databaseUrl().includes("localhost") ? undefined : { rejectUnauthorized: false },
  max: 1,
});

async function main() {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    await client.query(`
      CREATE TABLE IF NOT EXISTS user_profile (
        id text PRIMARY KEY DEFAULT 'default',
        sex text NOT NULL DEFAULT 'male',
        date_of_birth text NOT NULL DEFAULT '2000-06-28',
        height_cm numeric NOT NULL DEFAULT 165,
        bmr_formula text NOT NULL DEFAULT 'auto',
        updated_at timestamptz NOT NULL DEFAULT now()
      )
    `);
    await client.query(`
      INSERT INTO user_profile (id, sex, date_of_birth, height_cm, bmr_formula)
      VALUES ('default', 'male', '2000-06-28', 165, 'auto')
      ON CONFLICT (id) DO NOTHING
    `);
    await client.query("ALTER TABLE pending_writes ADD COLUMN IF NOT EXISTS dedupe_key text");
    await client.query(`
      CREATE UNIQUE INDEX IF NOT EXISTS idx_pending_writes_pending_dedupe_key
      ON pending_writes (dedupe_key)
      WHERE dedupe_key IS NOT NULL AND status = 'pending'
    `);
    await client.query("COMMIT");
    console.log("Profile/write schema is ready.");
  } catch (error) {
    await client.query("ROLLBACK").catch(() => {});
    throw error;
  } finally {
    client.release();
    await pool.end();
  }
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : error);
  process.exit(1);
});
