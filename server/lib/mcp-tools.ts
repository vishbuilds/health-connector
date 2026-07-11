import { randomUUID } from "crypto";
import { and, count, eq, isNull, max, min, sql } from "drizzle-orm";
import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { db, runReadOnlyQuery, READ_ONLY_LIMITS } from "@/db/client";
import { healthRecords, pendingWrites, userGoals, userProfile } from "@/db/schema";
import { getGoals } from "./goals";
import { buildHealthProfileSnapshot, getUserProfile, USER_TIMEZONE } from "./profile";
import { RECORD_TYPES } from "./record-types";
import { WRITABLE_TYPES, WRITABLE_TYPE_WIRE_NAMES } from "./write-types";
import { searchFoods } from "./food-db";

const isoInstant = z
  .string()
  .datetime({ offset: true })
  .describe("ISO-8601 instant with offset, e.g. 2026-07-06T08:00:00+10:00");

/**
 * The jsonb `data` payload shape for each record type, matching the Android wire format
 * (network/dto/RecordDto.kt::toUpsertJson). Keys carry an explicit unit suffix. Durations for
 * interval records (sleep, exercise) are NOT in `data` — derive them from end_time - start_time.
 * Exposed to the model as the `health://data-shapes` resource and referenced by the query tool.
 */
const DATA_SHAPES: Record<string, string> = {
  StepsRecord: "{ count: number }",
  DistanceRecord: "{ distanceMeters: number }",
  ActiveCaloriesBurnedRecord: "{ energyKcal: number }",
  TotalCaloriesBurnedRecord: "{ energyKcal: number } (per-interval burn; Hevy writes one row per strength workout — count these toward expenditure. Still not a full-day feed: don't treat a single row or source as the whole day's total)",
  FloorsClimbedRecord: "{ floors: number }",
  HydrationRecord: "{ volumeLiters: number }",
  SleepSessionRecord: "{ title?, notes?, stages: [...] } (duration = end_time - start_time)",
  ExerciseSessionRecord:
    "{ exerciseType, title?, notes?, segmentCount, lapCount } (duration = end_time - start_time)",
  HeartRateRecord: "{ samples: [{ time, beatsPerMinute }] }",
  RestingHeartRateRecord: "{ restingHeartRateBpm: number }",
  WeightRecord: "{ weightKg: number }",
  HeightRecord: "{ heightMeters: number }",
  BodyFatRecord: "{ bodyFatPercentage: number }",
  BasalMetabolicRateRecord: "{ basalMetabolicRateKcalPerDay: number, basalMetabolicRateWatts?: number }",
  BodyTemperatureRecord: "{ temperatureCelsius: number }",
  BloodPressureRecord: "{ systolicMmHg: number, diastolicMmHg: number }",
  BloodGlucoseRecord: "{ levelMgPerDl: number }",
  OxygenSaturationRecord: "{ oxygenSaturationPercentage: number }",
  RespiratoryRateRecord: "{ respiratoryRateBreathsPerMinute: number }",
  NutritionRecord: "{ name?, mealType?, energyKcal?, proteinGrams?, totalCarbohydrateGrams?, totalFatGrams? }",
};

export function registerHealthTools(server: McpServer) {
  registerReadTool(server);
  registerWriteTool(server);
  registerFoodMacrosTool(server);
  registerGoals(server);
  registerProfile(server);
  registerResources(server);
  registerPrompts(server);
}

// ---------------------------------------------------------------------------
// Read: one general query primitive over the health database.
// ---------------------------------------------------------------------------

/**
 * Strips comments and enforces a single statement that starts with SELECT/WITH. This is only a
 * fast-fail for obvious mistakes and good error messages — the actual read-only guarantee comes
 * from `runReadOnlyQuery` executing inside a READ ONLY transaction (see db/client.ts).
 */
function sanitizeSelect(raw: string): string {
  const stripped = raw
    .replace(/\/\*[\s\S]*?\*\//g, " ")
    .replace(/--[^\n]*/g, " ")
    .trim()
    .replace(/;\s*$/, "");
  if (!stripped) throw new Error("empty query");
  if (stripped.includes(";")) throw new Error("only a single statement is allowed (remove any ';')");
  if (!/^(with|select)\b/i.test(stripped)) {
    throw new Error("only read-only SELECT / WITH queries are allowed");
  }
  return stripped;
}

const QUERY_DESCRIPTION = `Run a single read-only SQL query (SELECT / WITH) against the health database and get rows back as JSON. This is the primary way to READ data of any shape.

Tables:
  health_records(
    id text, record_type text, start_time timestamptz, end_time timestamptz,
    zone_offset text, data jsonb, source_app text, device_id text,
    synced_at timestamptz, deleted_at timestamptz)
    -- one row per Health Connect record. ALWAYS filter live rows with "deleted_at IS NULL".
    -- record_type is the Health Connect class simple name, e.g. 'StepsRecord'
    --   (catalog + per-type counts/date-range: read the health://record-types resource).
    -- data is a jsonb payload whose keys depend on record_type
    --   (per-type shapes: read the health://data-shapes resource).
  pending_writes(
    id text, record_type text, start_time timestamptz, end_time timestamptz, zone_offset text,
    data jsonb, status text, error text, health_connect_id text, dedupe_key text, created_at timestamptz,
    applied_at timestamptz)
    -- queued writes from write_records. status is one of 'pending' | 'applied' | 'failed'.
  user_profile(
    id text, sex text, date_of_birth text, height_cm numeric, bmr_formula text, updated_at timestamptz)
    -- canonical profile facts. Prefer get_health_profile / set_health_profile over raw SQL.

Conventions:
  - Times are stored in UTC. The user is in ${USER_TIMEZONE}. To group by local calendar day:
      (start_time AT TIME ZONE '${USER_TIMEZONE}')::date
  - Numeric fields live inside data as JSON text; cast them, e.g. (data->>'count')::numeric.
  - IMPORTANT for current/logged values: include pending_writes as well as health_records, because
    records queued with write_records are visible to the user/app before the phone has written them
    into Health Connect. Use this CTE pattern for "today", food, hydration, manual logs, and other
    current summaries:
      WITH actual_records AS (
        SELECT hr.id, hr.record_type, hr.start_time, hr.end_time, hr.data, hr.source_app
        FROM health_records hr
        WHERE hr.deleted_at IS NULL
          AND NOT EXISTS (
            SELECT 1 FROM pending_writes pw
            WHERE pw.status = 'pending'
              AND pw.health_connect_id IS NOT NULL
              AND pw.health_connect_id = hr.id
          )
      ),
      pending_records AS (
        SELECT id, record_type, start_time, end_time, data, '__pending_writes__'::text AS source_app
        FROM pending_writes pw
        WHERE (pw.status = 'pending' OR pw.status = 'applied')
          AND NOT EXISTS (
            SELECT 1 FROM health_records hr
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
      )
    Then query combined_records instead of health_records. If you need write status/debugging, query
    pending_writes directly.
  - De-duplicate multi-source metrics: several apps (phone + ring) can log the same activity, so
    naively summing double-counts. Sum within each source_app, then take the largest single
    source. Example — steps for one local day:
      SELECT max(t.total) AS steps FROM (
        SELECT source_app, sum((data->>'count')::numeric) AS total
        FROM combined_records
        WHERE record_type = 'StepsRecord'
          AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = DATE '2026-07-05'
        GROUP BY source_app
      ) t;
    (Single-source days are unaffected. Skip the dedup when you specifically want per-source rows.)
  - IMPORTANT for calories: TotalCaloriesBurnedRecord rows are per-interval, not a guaranteed
    full-day expenditure feed, so don't treat a single row or source as "today's total calories
    burned". But Hevy logs each strength workout as a TotalCaloriesBurnedRecord scoped to that
    session, and wearable active-calorie feeds barely register lifting — that burn is real
    expenditure. For daily calorie balance use active burn (ActiveCaloriesBurnedRecord) PLUS workout
    burn (TotalCaloriesBurnedRecord) PLUS basal burn from get_health_profile. De-duplicate each by
    source_app (sum per source, take the max), then add the three together.

Limits: one statement only; runs READ ONLY (writes are impossible); aborted after ${
  READ_ONLY_LIMITS.statementTimeoutMs / 1000
}s; at most ${READ_ONLY_LIMITS.maxRows} rows returned (add your own LIMIT / aggregation).`;

function registerReadTool(server: McpServer) {
  server.registerTool(
    "query_health_data",
    {
      title: "Query health data (read-only SQL)",
      description: QUERY_DESCRIPTION,
      inputSchema: {
        sql: z.string().min(1).describe("A single read-only SELECT or WITH statement."),
      },
    },
    async ({ sql: sqlText }) => {
      let query: string;
      try {
        query = sanitizeSelect(sqlText);
      } catch (e) {
        return { isError: true, content: [{ type: "text", text: `Rejected: ${(e as Error).message}` }] };
      }

      try {
        const rows = await runReadOnlyQuery(query);
        const truncated = rows.length > READ_ONLY_LIMITS.maxRows;
        const out = truncated ? rows.slice(0, READ_ONLY_LIMITS.maxRows) : rows;
        return {
          content: [
            {
              type: "text",
              text: JSON.stringify({ rowCount: out.length, truncated, rows: out }, null, 2),
            },
          ],
        };
      } catch (e) {
        return { isError: true, content: [{ type: "text", text: `Query error: ${(e as Error).message}` }] };
      }
    },
  );

  server.registerTool(
    "list_logged_records",
    {
      title: "List Claude-written health records",
      description:
        "List records queued through write_records, including pending/applied/failed status, dedupeKey, " +
        "Health Connect id, timestamps, and data. Use this before updating a prior Claude-written log. " +
        "This does not list external records written by other apps; query health_records for those.",
      inputSchema: {
        status: z.enum(["pending", "applied", "failed"]).optional().describe("Optional status filter."),
        limit: z.number().int().min(1).max(100).optional().describe("Maximum rows to return, default 25."),
      },
    },
    async ({ status, limit }) => {
      const rows = status
        ? await db
            .select()
            .from(pendingWrites)
            .where(eq(pendingWrites.status, status))
            .orderBy(sql`${pendingWrites.createdAt} DESC`)
            .limit(limit ?? 25)
        : await db
            .select()
            .from(pendingWrites)
            .orderBy(sql`${pendingWrites.createdAt} DESC`)
            .limit(limit ?? 25);
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                rowCount: rows.length,
                rows: rows.map((r) => ({
                  id: r.id,
                  recordType: r.recordType,
                  status: r.status,
                  dedupeKey: r.dedupeKey,
                  startTime: r.startTime.toISOString(),
                  endTime: r.endTime?.toISOString() ?? null,
                  zoneOffset: r.zoneOffset,
                  data: r.data,
                  error: r.error,
                  healthConnectId: r.healthConnectId,
                  createdAt: r.createdAt.toISOString(),
                  appliedAt: r.appliedAt?.toISOString() ?? null,
                })),
              },
              null,
              2,
            ),
          },
        ],
      };
    },
  );

  server.registerTool(
    "update_logged_record",
    {
      title: "Update a Claude-written health record",
      description:
        "Update a record previously queued by write_records, addressed by id or dedupeKey. Pending rows " +
        "are edited in place. Applied rows are explicitly re-queued with the same server id/clientRecordId " +
        "so the phone can update that same Health Connect record on next sync; current summaries will show " +
        "the pending replacement and suppress the old Health Connect row. This tool will not mutate records " +
        "that came only from other apps and were never written through Claude.",
      inputSchema: {
        id: z.string().min(1).optional().describe("pending_writes.id returned by write_records/list_logged_records."),
        dedupeKey: z.string().min(1).max(300).optional().describe("Stable dedupe key for the record to update."),
        type: z.enum(WRITABLE_TYPE_WIRE_NAMES).optional().describe("Optional replacement record type."),
        startTime: isoInstant.optional().describe("Optional replacement start time."),
        endTime: isoInstant.optional().nullable().describe("Optional replacement end time; null clears it."),
        zoneOffset: z
          .string()
          .regex(/^[+-]\d{2}:\d{2}$/)
          .optional()
          .nullable()
          .describe("Optional replacement zone offset; null clears it."),
        data: z.record(z.string(), z.unknown()).optional().describe("Full replacement data object."),
        dataPatch: z.record(z.string(), z.unknown()).optional().describe("Shallow patch merged into existing data."),
      },
    },
    async ({ id, dedupeKey, type, startTime, endTime, zoneOffset, data, dataPatch }) => {
      if (!id && !dedupeKey) {
        return { isError: true, content: [{ type: "text", text: "Provide either id or dedupeKey." }] };
      }

      const [existing] = await db
        .select()
        .from(pendingWrites)
        .where(id ? eq(pendingWrites.id, id) : eq(pendingWrites.dedupeKey, cleanDedupeKey(dedupeKey!)))
        .orderBy(sql`${pendingWrites.createdAt} DESC`)
        .limit(1);

      if (!existing) {
        return {
          isError: true,
          content: [{ type: "text", text: "No Claude-written record found for that id/dedupeKey." }],
        };
      }

      const nextData = data ?? { ...(existing.data as Record<string, unknown>), ...(dataPatch ?? {}) };
      await db
        .update(pendingWrites)
        .set({
          recordType: type ?? existing.recordType,
          startTime: startTime ? new Date(startTime) : existing.startTime,
          endTime: endTime === undefined ? existing.endTime : endTime === null ? null : new Date(endTime),
          zoneOffset: zoneOffset === undefined ? existing.zoneOffset : zoneOffset,
          data: nextData,
          status: "pending",
          error: null,
          appliedAt: null,
          createdAt: new Date(),
        })
        .where(eq(pendingWrites.id, existing.id));

      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                updated: true,
                id: existing.id,
                dedupeKey: existing.dedupeKey,
                previousStatus: existing.status,
                requeued: true,
                note:
                  existing.status === "applied"
                    ? "Applied record re-queued with the same clientRecordId; the phone should update it on next write sync."
                    : "Pending record updated in place.",
              },
              null,
              2,
            ),
          },
        ],
      };
    },
  );
}

// ---------------------------------------------------------------------------
// Write: one batch tool. Reaching it requires the owner's MCP OAuth session
// (withMcpAuth), so writes are single-owner by construction. The only code-level
// gate is the type allowlist (the Zod enum); values/timestamps are guidance, and
// the phone's Health Connect insert is the real validator (failures surface as
// `failed` rows via /api/writes/ack).
// ---------------------------------------------------------------------------

type WriteOperation = "create_or_update" | "create";

interface RecordWriteInput {
  type: string;
  startTime: string;
  endTime?: string;
  zoneOffset?: string;
  data: Record<string, unknown>;
  dedupeKey?: string;
  operation?: WriteOperation;
}

function normalizeDedupePart(value: unknown): string {
  return String(value ?? "")
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 80);
}

function localDateTimeParts(isoInstant: string): { date: string; time: string } {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: USER_TIMEZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).formatToParts(new Date(isoInstant));
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? "00";
  const hour = String(Number(get("hour")) % 24).padStart(2, "0");
  return { date: `${get("year")}-${get("month")}-${get("day")}`, time: `${hour}:${get("minute")}` };
}

function defaultDedupeKey(record: RecordWriteInput): string {
  const { date, time } = localDateTimeParts(record.startTime);
  switch (record.type) {
    case "HeightRecord":
      return "HeightRecord|profile-height";
    case "WeightRecord":
      return `WeightRecord|${date}|bodyweight`;
    case "BodyFatRecord":
      return `BodyFatRecord|${date}|bodyfat`;
    case "BasalMetabolicRateRecord":
      return `BasalMetabolicRateRecord|${date}|bmr`;
    case "NutritionRecord": {
      const name = normalizeDedupePart(record.data.name);
      const mealType = normalizeDedupePart(record.data.mealType ?? "unknown");
      return `NutritionRecord|${date}|${time}|${mealType}|${name || "food"}`;
    }
    case "HydrationRecord":
      return `HydrationRecord|${date}|${time}|hydration`;
    default:
      return `${record.type}|${date}|${time}`;
  }
}

function cleanDedupeKey(key: string): string {
  return key.trim().replace(/\s+/g, " ").slice(0, 300);
}

function tzNow(): { date: string; minutes: number } {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: USER_TIMEZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).formatToParts(new Date());
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? "0";
  const hour = Number(get("hour")) % 24;
  return { date: `${get("year")}-${get("month")}-${get("day")}`, minutes: hour * 60 + Number(get("minute")) };
}

function registerWriteTool(server: McpServer) {
  const recordSchema = z.object({
    type: z.enum(WRITABLE_TYPE_WIRE_NAMES),
    startTime: isoInstant,
    endTime: isoInstant.optional().describe("Required for interval record types; omit for instantaneous ones."),
    zoneOffset: z
      .string()
      .regex(/^[+-]\d{2}:\d{2}$/)
      .optional()
      .describe("Optional zone offset like +10:00; defaults to the time's own offset."),
    data: z
      .record(z.string(), z.unknown())
      .describe("Type-specific fields; see the health://writable-types resource for names/units/ranges."),
    dedupeKey: z
      .string()
      .min(1)
      .max(300)
      .optional()
      .describe("Stable idempotency key. Omit to let the server derive one for common record types."),
    operation: z
      .enum(["create_or_update", "create"])
      .optional()
      .describe("create_or_update (default) updates a matching pending row by dedupeKey; create always queues a new row."),
  });

  server.registerTool(
    "write_records",
    {
      title: "Write Health Connect records",
      description:
        "Queue one or more records to be written into Health Connect on the phone. Only types from " +
        "the health://writable-types resource are accepted (all others are read-only). Writes are " +
        "applied on the phone's next sync (typically within ~15 minutes), not immediately. They are " +
        "readable immediately via the pending_writes table and should be included in current summaries " +
        "with the combined_records CTE described on query_health_data. Interval types (StepsRecord, " +
        "HydrationRecord, NutritionRecord) require endTime. Check status afterward by querying the " +
        "pending_writes table. Default behavior is create_or_update: if a pending row has the same " +
        "dedupeKey, it is updated instead of creating a duplicate. Already-applied Claude-written " +
        "records require update_logged_record so the replacement is explicit.",
      inputSchema: {
        records: z.array(recordSchema).min(1).max(500).describe("The records to queue."),
      },
    },
    async ({ records }) => {
      const results: { id: string; dedupeKey: string; action: "queued" | "updated_pending" }[] = [];

      for (const r of records) {
        const dedupeKey = cleanDedupeKey(r.dedupeKey ?? defaultDedupeKey(r));
        const row = {
          id: randomUUID(),
          recordType: r.type,
          startTime: new Date(r.startTime),
          endTime: r.endTime ? new Date(r.endTime) : null,
          zoneOffset: r.zoneOffset ?? null,
          data: r.data,
          dedupeKey,
        };

        if ((r.operation ?? "create_or_update") === "create_or_update") {
          const [existing] = await db
            .select({ id: pendingWrites.id })
            .from(pendingWrites)
            .where(and(eq(pendingWrites.status, "pending"), eq(pendingWrites.dedupeKey, dedupeKey)))
            .limit(1);

          if (existing) {
            await db
              .update(pendingWrites)
              .set({
                recordType: row.recordType,
                startTime: row.startTime,
                endTime: row.endTime,
                zoneOffset: row.zoneOffset,
                data: row.data,
                error: null,
              })
              .where(eq(pendingWrites.id, existing.id));
            results.push({ id: existing.id, dedupeKey, action: "updated_pending" });
            continue;
          }
        }

        await db.insert(pendingWrites).values(row);
        results.push({ id: row.id, dedupeKey, action: "queued" });
      }

      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                queued: results.filter((r) => r.action === "queued").length,
                updatedPending: results.filter((r) => r.action === "updated_pending").length,
                records: results,
                note: "Queued. Include these immediately in current summaries via pending_writes / the combined_records CTE from query_health_data. They will be written to Health Connect on the phone's next sync. Query pending_writes for status.",
              },
              null,
              2,
            ),
          },
        ],
      };
    },
  );
}

// ---------------------------------------------------------------------------
// Food macros: look up authoritative nutrition data before logging a NutritionRecord,
// so calories/protein/carbs/fat come from a database rather than a guess. Backed by a
// self-hosted `foods` table (AUSNUT 2023 generic Australian foods + Open Food Facts AU
// branded/packaged products), searched with Postgres full-text search — no external API.
// The model scales the per-100g macros to the actual portion, then calls write_records.
// ---------------------------------------------------------------------------

const FOOD_MACROS_DESCRIPTION = `Look up food nutrition/macros from a local Australian food database (AUSNUT 2023 generic foods + Open Food Facts Australia branded/packaged products). Use this to get accurate calories and macronutrients BEFORE logging a meal with write_records — do not estimate macros from memory.

Returns up to \`max_results\` matching foods, ranked by relevance. IMPORTANT: all macro values are PER 100 g (edible portion): energy_kcal, protein_g, carb_g, fat_g, plus fiber_g / sugar_g / sodium_mg when available. \`source\` is "ausnut" (generic AU foods, best for whole/home-cooked foods) or "off" (branded packaged products; \`brand\` and \`barcode\` are set). \`serving_desc\`/\`serving_g\` are optional serving hints from OFF.

How to use the result:
  1. Pick the food that best matches what was actually eaten (prefer a branded "off" match when the user named a brand; prefer "ausnut" for generic/whole foods).
  2. Scale the per-100g macros to the real portion: for 150 g, multiply every macro by 1.5; for a serving given in grams, use that. Sodium is in mg.
  3. Call write_records with a NutritionRecord: data { name, mealType (1 BREAKFAST / 2 LUNCH / 3 DINNER / 4 SNACK), energyKcal, proteinGrams, totalCarbohydrateGrams, totalFatGrams }. NutritionRecord is an interval type — set startTime and endTime.

If the first query returns nothing, retry with fewer/simpler words (matching requires all terms). If nothing sensible matches, tell the user rather than guessing.`;

function registerFoodMacrosTool(server: McpServer) {
  server.registerTool(
    "food_macros_search",
    {
      title: "Look up food macros (Australian food database)",
      description: FOOD_MACROS_DESCRIPTION,
      inputSchema: {
        query: z
          .string()
          .min(1)
          .describe('What to look up, e.g. "grilled chicken breast", "Weet-Bix", or a barcode.'),
        max_results: z
          .number()
          .int()
          .min(1)
          .max(15)
          .optional()
          .describe("How many candidate foods to return (default 8)."),
      },
    },
    async ({ query, max_results }) => {
      try {
        const foods = await searchFoods(query, max_results ?? 8);
        return {
          content: [
            {
              type: "text",
              text: JSON.stringify(
                {
                  query,
                  count: foods.length,
                  macros_basis: "per_100g",
                  foods,
                  note: "Macros are per 100 g. Pick the best match, scale to the actual portion, then log with write_records (NutritionRecord). If empty, retry with simpler terms.",
                },
                null,
                2,
              ),
            },
          ],
        };
      } catch (e) {
        return { isError: true, content: [{ type: "text", text: `Lookup failed: ${(e as Error).message}` }] };
      }
    },
  );
}

// ---------------------------------------------------------------------------
// Goals: the user's daily targets and bodyweight objective.
// Readable as a tool/resource and writable via a tool. Single-owner by construction —
// the write path is gated by the owner's MCP OAuth session, same as write_records.
// Backs the app's Home screen progress rings via GET /api/home.
// ---------------------------------------------------------------------------

function registerGoals(server: McpServer) {
  server.registerResource(
    "goals",
    "health://goals",
    {
      title: "Daily health goals",
      description:
        "The user's current targets. Daily: steps, sleep (minutes), hydration (liters), protein " +
        "(grams). Weekly: workout sessions. Directional: target bodyweight (kg, or null if none). " +
        "The Home screen derives calorie deficit/surplus from latest bodyweight at 0.5%/week. " +
        "Update goals with set_goals.",
      mimeType: "application/json",
    },
    async (uri) => {
      const goals = await getGoals();
      return { contents: [{ uri: uri.href, mimeType: "application/json", text: JSON.stringify(goals, null, 2) }] };
    },
  );

  server.registerTool(
    "get_goals",
    {
      title: "Get current health goals",
      description:
        "Read the user's current targets that power the Home screen. Daily: steps, sleep " +
        "(minutes), hydration (liters), protein (grams). Weekly: workout sessions. Directional: " +
        "target bodyweight (kg, or null if none). Calorie balance is derived from latest bodyweight " +
        "at 0.5%/week rather than manually targeted. Use this before " +
        "answering questions about current targets or before changing them with set_goals.",
      inputSchema: {},
    },
    async () => {
      const goals = await getGoals();
      return {
        content: [{ type: "text", text: JSON.stringify({ goals }, null, 2) }],
      };
    },
  );

  server.registerTool(
    "set_goals",
    {
      title: "Set daily health goals",
      description:
        "Update the user's targets that power the Home screen. Every field is optional — pass only " +
        "the ones you want to change; the rest keep their current value. Sleep is in MINUTES (e.g. " +
        "480 = 8h), hydration in LITERS, protein in GRAMS/day, steps are daily counts, " +
        "weeklyWorkoutTarget is sessions/week, weightTargetKg is the target bodyweight in KG " +
        "(pass 0 to clear it). The Home calorie-balance target is derived from latest bodyweight at " +
        "0.5%/week: deficit when above target, surplus when below target, maintenance at target. A " +
        "good protein target for muscle retention in a deficit is ~1.8 g per kg of bodyweight. Read " +
        "current values with get_goals first.",
      inputSchema: {
        stepsTarget: z.number().int().positive().optional().describe("Daily step goal, e.g. 10000."),
        sleepMinutesTarget: z.number().int().positive().optional().describe("Daily sleep goal in MINUTES, e.g. 480 for 8h."),
        hydrationLitersTarget: z.number().positive().optional().describe("Daily hydration goal in LITERS, e.g. 2.5."),
        proteinGramsTarget: z.number().int().positive().optional().describe("Daily protein goal in GRAMS, e.g. 150."),
        weeklyWorkoutTarget: z.number().int().positive().optional().describe("Target workout sessions per week, e.g. 4."),
        weightTargetKg: z
          .number()
          .min(0)
          .optional()
          .describe("Target bodyweight in KG. Pass 0 to clear the target."),
      },
    },
    async (updates) => {
      // weightTargetKg is stored as a nullable numeric; 0 is the sentinel for "clear the target".
      const weightTarget =
        updates.weightTargetKg === undefined ? undefined : updates.weightTargetKg === 0 ? null : String(updates.weightTargetKg);

      const set: Record<string, unknown> = { updatedAt: new Date() };
      if (updates.stepsTarget !== undefined) set.stepsTarget = updates.stepsTarget;
      if (updates.sleepMinutesTarget !== undefined) set.sleepMinutesTarget = updates.sleepMinutesTarget;
      if (updates.hydrationLitersTarget !== undefined) set.hydrationLitersTarget = String(updates.hydrationLitersTarget);
      if (updates.proteinGramsTarget !== undefined) set.proteinGramsTarget = updates.proteinGramsTarget;
      if (updates.weeklyWorkoutTarget !== undefined) set.weeklyWorkoutTarget = updates.weeklyWorkoutTarget;
      if (weightTarget !== undefined) set.weightTargetKg = weightTarget;

      await db
        .insert(userGoals)
        .values({
          id: "default",
          ...(updates.stepsTarget !== undefined ? { stepsTarget: updates.stepsTarget } : {}),
          ...(updates.sleepMinutesTarget !== undefined ? { sleepMinutesTarget: updates.sleepMinutesTarget } : {}),
          ...(updates.hydrationLitersTarget !== undefined
            ? { hydrationLitersTarget: String(updates.hydrationLitersTarget) }
            : {}),
          ...(updates.proteinGramsTarget !== undefined ? { proteinGramsTarget: updates.proteinGramsTarget } : {}),
          ...(updates.weeklyWorkoutTarget !== undefined ? { weeklyWorkoutTarget: updates.weeklyWorkoutTarget } : {}),
          ...(weightTarget !== undefined ? { weightTargetKg: weightTarget } : {}),
        })
        .onConflictDoUpdate({ target: userGoals.id, set });

      const goals = await getGoals();
      return {
        content: [{ type: "text", text: JSON.stringify({ updated: true, goals }, null, 2) }],
      };
    },
  );
}

function registerProfile(server: McpServer) {
  const profileSnapshot = async (date?: string) => {
    const now = tzNow();
    const targetDate = date && /^\d{4}-\d{2}-\d{2}$/.test(date) ? date : now.date;
    return buildHealthProfileSnapshot({
      targetDate,
      isToday: targetDate === now.date,
      nowMinutes: now.minutes,
    });
  };

  server.registerResource(
    "profile",
    "health://profile",
    {
      title: "Health profile and derived BMR",
      description:
        "Canonical profile facts plus latest weight/body-fat, measured-or-derived BMR, and basal calories elapsed today.",
      mimeType: "application/json",
    },
    async (uri) => {
      const snapshot = await profileSnapshot();
      return { contents: [{ uri: uri.href, mimeType: "application/json", text: JSON.stringify(snapshot, null, 2) }] };
    },
  );

  server.registerTool(
    "get_health_profile",
    {
      title: "Get health profile and BMR",
      description:
        "Read canonical profile facts (sex, DOB, height, BMR formula preference) plus latest weight/body-fat, " +
        "measured BMR if present, derived BMR if needed, and basal calories elapsed for the requested local date.",
      inputSchema: {
        date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional().describe("YYYY-MM-DD in Australia/Sydney. Defaults to today."),
      },
    },
    async ({ date }) => {
      const snapshot = await profileSnapshot(date);
      return { content: [{ type: "text", text: JSON.stringify(snapshot, null, 2) }] };
    },
  );

  server.registerTool(
    "set_health_profile",
    {
      title: "Set health profile",
      description:
        "Update canonical owner profile facts used by Home and MCP BMR calculations. Use this for stable profile " +
        "facts, not timestamped logs. If the user wants to log a measured height or BMR into Health Connect too, " +
        "also use write_records for HeightRecord or BasalMetabolicRateRecord.",
      inputSchema: {
        sex: z.enum(["male", "female"]).optional(),
        dateOfBirth: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional().describe("YYYY-MM-DD."),
        heightCm: z.number().positive().max(300).optional().describe("Height in centimeters."),
        bmrFormula: z.enum(["auto", "katch_mcardle", "mifflin_st_jeor"]).optional(),
      },
    },
    async (updates) => {
      const current = await getUserProfile();
      const next = {
        sex: updates.sex ?? current.sex,
        dateOfBirth: updates.dateOfBirth ?? current.dateOfBirth,
        heightCm: updates.heightCm ?? current.heightCm,
        bmrFormula: updates.bmrFormula ?? current.bmrFormula,
      };

      await db
        .insert(userProfile)
        .values({
          id: "default",
          sex: next.sex,
          dateOfBirth: next.dateOfBirth,
          heightCm: String(next.heightCm),
          bmrFormula: next.bmrFormula,
        })
        .onConflictDoUpdate({
          target: userProfile.id,
          set: {
            sex: next.sex,
            dateOfBirth: next.dateOfBirth,
            heightCm: String(next.heightCm),
            bmrFormula: next.bmrFormula,
            updatedAt: sql`now()`,
          },
        });

      const snapshot = await profileSnapshot();
      return { content: [{ type: "text", text: JSON.stringify({ updated: true, profile: snapshot }, null, 2) }] };
    },
  );
}

// ---------------------------------------------------------------------------
// Resources: read-only reference context (catalogs + jsonb shapes). These are
// data the model reads, not actions — so they're Resources, not tools.
// ---------------------------------------------------------------------------

function registerResources(server: McpServer) {
  server.registerResource(
    "record-types",
    "health://record-types",
    {
      title: "Health Connect record types",
      description:
        "Every record type this connector knows about, its category, how many live records are synced, and the earliest/latest record time for each.",
      mimeType: "application/json",
    },
    async (uri) => {
      const rows = await db
        .select({
          recordType: healthRecords.recordType,
          recordCount: count(),
          earliest: min(healthRecords.startTime),
          latest: max(healthRecords.startTime),
        })
        .from(healthRecords)
        .where(isNull(healthRecords.deletedAt))
        .groupBy(healthRecords.recordType);

      const byType = new Map(rows.map((r) => [r.recordType, r]));
      const result = RECORD_TYPES.map(({ wireName, category }) => {
        const row = byType.get(wireName);
        return {
          type: wireName,
          category,
          recordCount: row?.recordCount ?? 0,
          earliest: row?.earliest ? row.earliest.toISOString() : null,
          latest: row?.latest ? row.latest.toISOString() : null,
        };
      });

      return { contents: [{ uri: uri.href, mimeType: "application/json", text: JSON.stringify(result, null, 2) }] };
    },
  );

  server.registerResource(
    "data-shapes",
    "health://data-shapes",
    {
      title: "jsonb data shapes per record type",
      description:
        "The keys/units inside the health_records.data jsonb column for each record type. Use when writing SQL that reads data->>'field'.",
      mimeType: "application/json",
    },
    async (uri) => ({
      contents: [{ uri: uri.href, mimeType: "application/json", text: JSON.stringify(DATA_SHAPES, null, 2) }],
    }),
  );

  server.registerResource(
    "writable-types",
    "health://writable-types",
    {
      title: "Writable Health Connect record types",
      description:
        "The record types write_records may write, with their data fields, units, sensible ranges, and whether endTime is required. All other types are read-only.",
      mimeType: "application/json",
    },
    async (uri) => {
      const result = WRITABLE_TYPES.map((t) => ({
        type: t.wireName,
        shape: t.shape,
        endTimeRequired: t.shape === "interval",
        description: t.description,
      }));
      return { contents: [{ uri: uri.href, mimeType: "application/json", text: JSON.stringify(result, null, 2) }] };
    },
  );
}

// ---------------------------------------------------------------------------
// Prompts: canned analyses that would otherwise tempt bespoke report tools.
// Each just tells the model how to drive query_health_data.
// ---------------------------------------------------------------------------

function registerPrompts(server: McpServer) {
  const userText = (text: string) => ({
    messages: [{ role: "user" as const, content: { type: "text" as const, text } }],
  });

  server.registerPrompt(
    "daily_summary",
    {
      title: "Daily health summary",
      description: "Summarize one calendar day: steps, distance, calories, floors, hydration, sleep, resting HR, exercise, weight.",
      argsSchema: { date: z.string().describe("YYYY-MM-DD (Australia/Sydney). Defaults to today.").optional() },
    },
    ({ date }) =>
      userText(
        `Summarize my health for ${date ? `${date} (${USER_TIMEZONE})` : `today in ${USER_TIMEZONE}`}. ` +
          "Use query_health_data with the combined_records CTE described in the tool instructions so pending writes " +
          "are included, grouping by " +
          `(start_time AT TIME ZONE '${USER_TIMEZONE}')::date. Report steps, distance, active calories, estimated total burn, ` +
          "floors climbed, hydration, sleep duration, resting heart rate, exercise sessions, and latest weight " +
          "as of that day. Estimate total burn as active calories plus per-workout burn (Hevy's " +
          "TotalCaloriesBurnedRecord) plus basal burn from get_health_profile; a single " +
          "TotalCaloriesBurnedRecord row or source is still not the full-day total. " +
          "De-duplicate summed metrics by source_app (sum per source, take the max). " +
          "Consult health://data-shapes for the jsonb field names.",
      ),
  );

  server.registerPrompt(
    "weekly_summary",
    {
      title: "Weekly health summary",
      description: "Summarize and spot trends across a 7-day window.",
      argsSchema: { endDate: z.string().describe("Last day of the window, YYYY-MM-DD. Defaults to today.").optional() },
    },
    ({ endDate }) =>
      userText(
        `Summarize my health for the 7 days ending ${endDate ? `${endDate} (${USER_TIMEZONE})` : `today (${USER_TIMEZONE})`}. ` +
          "Use query_health_data with the combined_records CTE described in the tool instructions, then a per-local-day " +
          "GROUP BY to build a daily table (steps, sleep, active calories, " +
          "resting HR, hydration), then call out trends, best/worst days, and anything notable. De-duplicate summed " +
          "metrics by source_app. See health://data-shapes for field names.",
      ),
  );

  server.registerPrompt(
    "sleep_vs_activity",
    {
      title: "Sleep vs. activity",
      description: "Explore how sleep relates to next-day activity.",
      argsSchema: { days: z.string().describe("How many recent days to consider. Defaults to 30.").optional() },
    },
    ({ days }) =>
      userText(
        `Over the last ${days ?? "30"} days (${USER_TIMEZONE}), examine how my sleep duration relates to my ` +
          "activity (steps and active calories) the following day. Use query_health_data with the combined_records CTE " +
          "described in the tool instructions to build a per-day table " +
          "(sleep minutes = sum of end_time - start_time for SleepSessionRecord, de-duplicated by source_app), then " +
          "align each night with the next day's activity and describe any relationship you see.",
      ),
  );
}
