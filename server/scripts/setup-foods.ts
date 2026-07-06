/**
 * Creates the `foods` reference table that backs the food_macros_search MCP tool.
 * All macros are stored PER 100 g (edible portion) so AUSNUT and Open Food Facts
 * rows are directly comparable; the model scales to the actual portion at log time.
 *
 * Run: npx tsx --env-file=.env.local scripts/setup-foods.ts
 */
import { makePool } from "./_foods-db";

const DDL = `
CREATE TABLE IF NOT EXISTS foods (
  id           text PRIMARY KEY,         -- 'ausnut:<survey id>' | 'off:<barcode>'
  source       text NOT NULL,            -- 'ausnut' | 'off'
  name         text NOT NULL,
  brand        text,
  barcode      text,
  energy_kcal  numeric,                  -- all macros are per 100 g
  protein_g    numeric,
  carb_g       numeric,
  fat_g        numeric,
  fiber_g      numeric,
  sugar_g      numeric,
  sodium_mg    numeric,
  serving_desc text,                     -- free-text serving hint (OFF), if any
  serving_g    numeric,                  -- grams per serving (OFF), if known
  updated_at   timestamptz NOT NULL DEFAULT now()
);

DROP INDEX IF EXISTS idx_foods_search;
ALTER TABLE foods DROP COLUMN IF EXISTS search_tsv;
ALTER TABLE foods ADD COLUMN search_tsv tsvector GENERATED ALWAYS AS (
  setweight(to_tsvector('english', coalesce(name, '')), 'A') ||
  setweight(to_tsvector('english', coalesce(brand, '')), 'B') ||
  setweight(to_tsvector('simple', coalesce(barcode, '')), 'C')
) STORED;

CREATE INDEX IF NOT EXISTS idx_foods_search  ON foods USING GIN (search_tsv);
CREATE INDEX IF NOT EXISTS idx_foods_barcode ON foods (barcode) WHERE barcode IS NOT NULL;
`;

async function main() {
  const pool = makePool();
  try {
    await pool.query(DDL);
    const { rows } = await pool.query<{ n: string }>("SELECT count(*)::text AS n FROM foods");
    console.log(`foods table ready. current rows: ${rows[0]?.n ?? "0"}`);
  } finally {
    await pool.end();
  }
}

main().catch((e) => {
  console.error("setup-foods failed:", e.message);
  process.exit(1);
});
