// Full-text search over the self-hosted `foods` table (AUSNUT 2023 + Open Food Facts
// AU), which backs the food_macros_search MCP tool. All macros are per 100 g.
import { sql } from "drizzle-orm";
import { db } from "@/db/client";

export interface FoodMatch {
  id: string;
  source: "ausnut" | "off";
  name: string;
  brand: string | null;
  barcode: string | null;
  energy_kcal: number | null;
  protein_g: number | null;
  carb_g: number | null;
  fat_g: number | null;
  fiber_g: number | null;
  sugar_g: number | null;
  sodium_mg: number | null;
  serving_desc: string | null;
  serving_g: number | null;
}

// pg returns `numeric` columns as strings — coerce to real numbers for clean JSON.
const toNum = (v: unknown): number | null => (v === null || v === undefined ? null : Number(v));
const toStr = (v: unknown): string | null => (v === null || v === undefined ? null : String(v));

/**
 * Rank foods by exact barcode or relevance to `query` via Postgres full-text search.
 * Ties break toward AUSNUT (curated generic foods) and rows that carry an energy value.
 */
export async function searchFoods(query: string, limit: number): Promise<FoodMatch[]> {
  const q = query.trim();
  if (!q) return [];

  const result = await db.execute(sql`
    SELECT id, source, name, brand, barcode,
           energy_kcal, protein_g, carb_g, fat_g, fiber_g, sugar_g, sodium_mg,
           serving_desc, serving_g
    FROM foods
    WHERE barcode = ${q}
       OR search_tsv @@ websearch_to_tsquery('english', ${q})
    ORDER BY (barcode = ${q}) DESC,
             ts_rank(search_tsv, websearch_to_tsquery('english', ${q})) DESC,
             (source = 'ausnut') DESC,
             (energy_kcal IS NOT NULL) DESC
    LIMIT ${limit}
  `);

  // node-postgres returns { rows }; neon-http returns the array directly.
  const rows = ((result as { rows?: unknown[] }).rows ?? result) as Record<string, unknown>[];
  return rows.map((r) => ({
    id: String(r.id),
    source: r.source as "ausnut" | "off",
    name: String(r.name),
    brand: toStr(r.brand),
    barcode: toStr(r.barcode),
    energy_kcal: toNum(r.energy_kcal),
    protein_g: toNum(r.protein_g),
    carb_g: toNum(r.carb_g),
    fat_g: toNum(r.fat_g),
    fiber_g: toNum(r.fiber_g),
    sugar_g: toNum(r.sugar_g),
    sodium_mg: toNum(r.sodium_mg),
    serving_desc: toStr(r.serving_desc),
    serving_g: toNum(r.serving_g),
  }));
}
