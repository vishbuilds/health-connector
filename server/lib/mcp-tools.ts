import { randomUUID } from "crypto";
import { count, isNull, max, min } from "drizzle-orm";
import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { db, runReadOnlyQuery, READ_ONLY_LIMITS } from "@/db/client";
import { healthRecords, pendingWrites } from "@/db/schema";
import { RECORD_TYPES } from "./record-types";
import { WRITABLE_TYPES, WRITABLE_TYPE_WIRE_NAMES } from "./write-types";

const USER_TIMEZONE = "Australia/Sydney";

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
  TotalCaloriesBurnedRecord: "{ energyKcal: number }",
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
    data jsonb, status text, error text, health_connect_id text, created_at timestamptz,
    applied_at timestamptz)
    -- queued writes from write_records. status is one of 'pending' | 'applied' | 'failed'.

Conventions:
  - Times are stored in UTC. The user is in ${USER_TIMEZONE}. To group by local calendar day:
      (start_time AT TIME ZONE '${USER_TIMEZONE}')::date
  - Numeric fields live inside data as JSON text; cast them, e.g. (data->>'count')::numeric.
  - De-duplicate multi-source metrics: several apps (phone + ring) can log the same activity, so
    naively summing double-counts. Sum within each source_app, then take the largest single
    source. Example — steps for one local day:
      SELECT max(t.total) AS steps FROM (
        SELECT source_app, sum((data->>'count')::numeric) AS total
        FROM health_records
        WHERE record_type = 'StepsRecord' AND deleted_at IS NULL
          AND (start_time AT TIME ZONE '${USER_TIMEZONE}')::date = DATE '2026-07-05'
        GROUP BY source_app
      ) t;
    (Single-source days are unaffected. Skip the dedup when you specifically want per-source rows.)

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
}

// ---------------------------------------------------------------------------
// Write: one batch tool. Reaching it requires the owner's MCP OAuth session
// (withMcpAuth), so writes are single-owner by construction. The only code-level
// gate is the type allowlist (the Zod enum); values/timestamps are guidance, and
// the phone's Health Connect insert is the real validator (failures surface as
// `failed` rows via /api/writes/ack).
// ---------------------------------------------------------------------------

function registerWriteTool(server: McpServer) {
  const isoInstant = z
    .string()
    .datetime({ offset: true })
    .describe("ISO-8601 instant with offset, e.g. 2026-07-06T08:00:00+10:00");

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
  });

  server.registerTool(
    "write_records",
    {
      title: "Write Health Connect records",
      description:
        "Queue one or more records to be written into Health Connect on the phone. Only types from " +
        "the health://writable-types resource are accepted (all others are read-only). Writes are " +
        "applied on the phone's next sync (typically within ~15 minutes), not immediately, and then " +
        "become readable via query_health_data. Interval types (StepsRecord, HydrationRecord, " +
        "NutritionRecord) require endTime. Check status afterward by querying the pending_writes table.",
      inputSchema: {
        records: z.array(recordSchema).min(1).max(500).describe("The records to queue."),
      },
    },
    async ({ records }) => {
      const rows = records.map((r) => ({
        id: randomUUID(),
        recordType: r.type,
        startTime: new Date(r.startTime),
        endTime: r.endTime ? new Date(r.endTime) : null,
        zoneOffset: r.zoneOffset ?? null,
        data: r.data,
      }));

      await db.insert(pendingWrites).values(rows);

      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                queued: rows.length,
                ids: rows.map((r) => r.id),
                note: "Queued. Will be written to Health Connect on the phone's next sync, then readable via query_health_data. Query pending_writes for status.",
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
          "Use query_health_data against health_records, grouping by " +
          `(start_time AT TIME ZONE '${USER_TIMEZONE}')::date. Report steps, distance, active/total calories, ` +
          "floors climbed, hydration, sleep duration, resting heart rate, exercise sessions, and latest weight " +
          "as of that day. De-duplicate summed metrics by source_app (sum per source, take the max). " +
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
          "Use query_health_data with a per-local-day GROUP BY to build a daily table (steps, sleep, active calories, " +
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
          "activity (steps and active calories) the following day. Use query_health_data to build a per-day table " +
          "(sleep minutes = sum of end_time - start_time for SleepSessionRecord, de-duplicated by source_app), then " +
          "align each night with the next day's activity and describe any relationship you see.",
      ),
  );
}
