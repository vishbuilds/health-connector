/**
 * Imports AUSNUT 2023 (FSANZ) generic Australian foods into the `foods` table.
 * Source: "AUSNUT 2023 - Food nutrient profiles.xlsx" (per-100 g values, one row per food).
 *
 * Attribution (required, CC BY 3.0 AU):
 *   "Based on AUSNUT 2023 © Food Standards Australia New Zealand, used under a
 *    Creative Commons Attribution 3.0 Australia licence."
 *
 * Run: npx tsx --env-file=.env.local scripts/import-ausnut.ts
 * Expects .context/ausnut/nutrients.xlsx (downloaded from foodstandards.gov.au).
 */
import { readFileSync } from "fs";
import xlsx from "xlsx";
import { makePool, upsertFoods, num, type FoodRow } from "./_foods-db";

const FILE = ".context/ausnut/nutrients.xlsx";
const SHEET = "Food nutrient profiles";
const KJ_PER_KCAL = 4.184;

// Exact AUSNUT 2023 column headers we read (verified against the file).
const COL = {
  id: "Survey ID",
  name: "Food name",
  energyKj: "Energy without dietary fibre (kJ)",
  protein: "Protein (g)",
  fat: "Total fat (g)",
  carb: "Available carbohydrate, without sugar alcohols (g)",
  sugar: "Total sugars (g)",
  fiber: "Dietary fibre (g)",
  sodium: "Sodium (Na) (mg)",
} as const;

function main() {
  const wb = xlsx.read(readFileSync(FILE), { type: "buffer" });
  const ws = wb.Sheets[SHEET];
  if (!ws) throw new Error(`sheet "${SHEET}" not found in ${FILE}`);

  const grid = xlsx.utils.sheet_to_json<unknown[]>(ws, { header: 1, blankrows: false });
  // Row 0 is a title banner; row 1 is the real header; data starts at row 2.
  const header = (grid[1] as string[]).map((h) => String(h ?? "").trim());
  const idx = (name: string) => {
    const i = header.indexOf(name);
    if (i === -1) throw new Error(`column "${name}" not found. Headers: ${header.join(" | ")}`);
    return i;
  };
  const ci = Object.fromEntries(Object.entries(COL).map(([k, v]) => [k, idx(v)])) as Record<
    keyof typeof COL,
    number
  >;

  const rows: FoodRow[] = [];
  for (let r = 2; r < grid.length; r++) {
    const cells = grid[r] as unknown[];
    const id = String(cells[ci.id] ?? "").trim();
    const name = String(cells[ci.name] ?? "").trim();
    if (!id || !name) continue;

    const energyKj = num(cells[ci.energyKj]);
    rows.push([
      `ausnut:${id}`,
      "ausnut",
      name,
      null, // brand — AUSNUT foods are generic
      null, // barcode
      energyKj === null ? null : Math.round(energyKj / KJ_PER_KCAL),
      num(cells[ci.protein]),
      num(cells[ci.carb]),
      num(cells[ci.fat]),
      num(cells[ci.fiber]),
      num(cells[ci.sugar]),
      num(cells[ci.sodium]),
      null, // serving_desc
      null, // serving_g
    ]);
  }

  return rows;
}

async function run() {
  const rows = main();
  console.log(`parsed ${rows.length} AUSNUT foods; upserting...`);
  const pool = makePool();
  try {
    const n = await upsertFoods(pool, rows);
    console.log(`upserted ${n} AUSNUT rows.`);
  } finally {
    await pool.end();
  }
}

run().catch((e) => {
  console.error("import-ausnut failed:", e.message);
  process.exit(1);
});
