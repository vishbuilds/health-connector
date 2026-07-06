/**
 * Imports the Australian subset of Open Food Facts (packaged/branded products with
 * barcodes) into the `foods` table. Streams the gzipped tab-separated full export and
 * keeps only rows whose countries_tags include 'en:australia'.
 *
 * Attribution (required, ODbL): "Contains information from Open Food Facts, which is
 * made available under the Open Database License (ODbL)."
 *
 * Run: npx tsx --env-file=.env.local scripts/import-off-au.ts
 * Expects .context/off/products.csv.gz (from static.openfoodfacts.org).
 */
import { createReadStream } from "fs";
import { createGunzip } from "zlib";
import { createInterface } from "readline";
import { makePool, upsertFoods, num, type FoodRow } from "./_foods-db";

const FILE = ".context/off/products.csv.gz";
const BATCH = 2000;

// OFF flat CSV headers (tab-separated). energy-kcal uses a hyphen; the rest underscores.
const NEEDED = [
  "code",
  "product_name",
  "brands",
  "countries_tags",
  "serving_size",
  "serving_quantity",
  "energy-kcal_100g",
  "proteins_100g",
  "carbohydrates_100g",
  "fat_100g",
  "fiber_100g",
  "sugars_100g",
  "sodium_100g",
  "salt_100g",
] as const;

async function run() {
  const pool = makePool();
  const rl = createInterface({
    input: createReadStream(FILE).pipe(createGunzip()),
    crlfDelay: Infinity,
  });

  type Col = (typeof NEEDED)[number];
  let colCount = 0;
  const ci = {} as Record<Col, number>;
  let batch: FoodRow[] = [];
  let seen = 0;
  let kept = 0;
  let wrote = 0;

  const flush = async () => {
    if (!batch.length) return;
    wrote += await upsertFoods(pool, batch);
    batch = [];
    process.stdout.write(`\r  scanned ${seen}  kept ${kept}  written ${wrote}   `);
  };

  try {
    for await (const line of rl) {
      if (!colCount) {
        // Header row: map the columns we need to their indices.
        const header = line.split("\t");
        colCount = header.length;
        for (const n of NEEDED) ci[n] = header.indexOf(n);
        const missing = NEEDED.filter((n) => ci[n] === -1);
        if (missing.length) throw new Error(`missing OFF columns: ${missing.join(", ")}`);
        continue;
      }

      seen++;
      const f = line.split("\t");
      // Drop rows misaligned by embedded newlines rather than corrupt the table.
      if (f.length !== colCount) continue;

      const countries = f[ci["countries_tags"]] ?? "";
      if (!countries.includes("en:australia")) continue;

      const code = (f[ci["code"]] ?? "").trim();
      const name = (f[ci["product_name"]] ?? "").trim();
      if (!code || !name) continue;

      const energy = num(f[ci["energy-kcal_100g"]]);
      const protein = num(f[ci["proteins_100g"]]);
      const carb = num(f[ci["carbohydrates_100g"]]);
      const fat = num(f[ci["fat_100g"]]);
      // Skip products with no usable macro data at all.
      if (energy === null && protein === null && carb === null && fat === null) continue;

      // sodium_100g is grams/100g -> mg; fall back to salt (g) via the 2.5 salt:sodium ratio.
      const sodiumG = num(f[ci["sodium_100g"]]);
      const saltG = num(f[ci["salt_100g"]]);
      const sodiumMg =
        sodiumG !== null ? Math.round(sodiumG * 1000) : saltG !== null ? Math.round(saltG * 400) : null;

      const brand = (f[ci["brands"]] ?? "").split(",")[0]?.trim() || null;

      batch.push([
        `off:${code}`,
        "off",
        name,
        brand,
        code,
        energy,
        protein,
        carb,
        fat,
        num(f[ci["fiber_100g"]]),
        num(f[ci["sugars_100g"]]),
        sodiumMg,
        (f[ci["serving_size"]] ?? "").trim() || null,
        num(f[ci["serving_quantity"]]),
      ]);
      kept++;
      if (batch.length >= BATCH) await flush();
    }
    await flush();
    process.stdout.write("\n");
    console.log(`done. scanned ${seen}, kept ${kept} AU products, wrote ${wrote}.`);
  } finally {
    await pool.end();
  }
}

run().catch((e) => {
  console.error("\nimport-off-au failed:", e.message);
  process.exit(1);
});
