// Shared helpers for the food-database ETL scripts (setup-foods / import-ausnut /
// import-off-au). These are one-off Node scripts, so they open their own pg Pool
// straight from DATABASE_URL rather than going through the Drizzle handle.
import { Pool } from "pg";

export function makePool(): Pool {
  const url = process.env.DATABASE_URL;
  if (!url) throw new Error("DATABASE_URL is not set (run with --env-file=.env.local)");
  const host = new URL(url).hostname;
  const isLocal = host === "localhost" || host === "127.0.0.1";
  // Mirror db/client.ts: managed Postgres (Supabase) needs TLS but its pooler cert
  // chain isn't always trusted, so don't hard-fail on verification.
  return new Pool({
    connectionString: url,
    ssl: isLocal ? undefined : { rejectUnauthorized: false },
    max: 4,
  });
}

// Column order used by every upsert. `id` is the conflict key.
export const FOOD_COLUMNS = [
  "id",
  "source",
  "name",
  "brand",
  "barcode",
  "energy_kcal",
  "protein_g",
  "carb_g",
  "fat_g",
  "fiber_g",
  "sugar_g",
  "sodium_mg",
  "serving_desc",
  "serving_g",
] as const;

export type FoodRow = (string | number | null)[];

/** Batched INSERT ... ON CONFLICT (id) DO UPDATE. Each row must match FOOD_COLUMNS order. */
export async function upsertFoods(pool: Pool, rows: FoodRow[]): Promise<number> {
  const cols = FOOD_COLUMNS;
  const chunkSize = 500;
  let written = 0;
  const setClause = cols
    .filter((c) => c !== "id")
    .map((c) => `${c} = EXCLUDED.${c}`)
    .join(", ");

  for (let i = 0; i < rows.length; i += chunkSize) {
    const chunk = rows.slice(i, i + chunkSize);
    const params: (string | number | null)[] = [];
    const tuples = chunk.map((r, ri) => {
      const placeholders = cols.map((_, ci) => `$${ri * cols.length + ci + 1}`);
      params.push(...r);
      return `(${placeholders.join(", ")})`;
    });
    await pool.query(
      `INSERT INTO foods (${cols.join(", ")}) VALUES ${tuples.join(", ")}
       ON CONFLICT (id) DO UPDATE SET ${setClause}, updated_at = now()`,
      params,
    );
    written += chunk.length;
  }
  return written;
}

/** Parse a numeric cell, returning null for blanks / non-numbers / negatives. */
export function num(v: unknown): number | null {
  if (v === undefined || v === null || v === "") return null;
  const n = typeof v === "number" ? v : Number(String(v).trim());
  if (!Number.isFinite(n) || n < 0) return null;
  // Round to 2 dp to keep the table tidy; macros don't need more precision.
  return Math.round(n * 100) / 100;
}
