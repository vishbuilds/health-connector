import { randomUUID } from "crypto";
import { and, count, desc, eq, gte, isNull, lt, max, min } from "drizzle-orm";
import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { db } from "@/db/client";
import { healthRecords, pendingWrites } from "@/db/schema";
import { RECORD_TYPES, RECORD_TYPE_WIRE_NAMES } from "./record-types";
import { getDailySummary, localDateToUtc } from "./daily-summary";
import { validateWrite, WRITABLE_TYPES, WRITABLE_TYPE_WIRE_NAMES } from "./write-types";

const typeEnum = z.enum(RECORD_TYPE_WIRE_NAMES);
const dateString = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "expected YYYY-MM-DD");

export function registerHealthTools(server: McpServer) {
  server.registerTool(
    "list_data_types",
    {
      title: "List Health Connect data types",
      description:
        "Lists every Health Connect record type this connector knows about, with how many records are synced and the earliest/latest record time for each. Types with zero synced records are still listed.",
      inputSchema: {},
    },
    async () => {
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

      return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
    },
  );

  server.registerTool(
    "get_records",
    {
      title: "Get Health Connect records",
      description:
        "Fetch raw Health Connect records of one type within a date range. Dates are interpreted as Australia/Sydney calendar days.",
      inputSchema: {
        type: typeEnum,
        startDate: dateString.describe("Inclusive start date, YYYY-MM-DD (Australia/Sydney)"),
        endDate: dateString.describe("Exclusive end date, YYYY-MM-DD (Australia/Sydney)"),
        limit: z.number().int().min(1).max(1000).default(100),
      },
    },
    async ({ type, startDate, endDate, limit }) => {
      const rows = await db
        .select()
        .from(healthRecords)
        .where(
          and(
            eq(healthRecords.recordType, type),
            gte(healthRecords.startTime, localDateToUtc(startDate)),
            lt(healthRecords.startTime, localDateToUtc(endDate)),
            isNull(healthRecords.deletedAt),
          ),
        )
        .orderBy(desc(healthRecords.startTime))
        .limit(limit);

      const result = rows.map((r) => ({
        id: r.id,
        startTime: r.startTime.toISOString(),
        endTime: r.endTime ? r.endTime.toISOString() : null,
        zoneOffset: r.zoneOffset,
        sourceApp: r.sourceApp,
        data: r.data,
      }));

      return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
    },
  );

  server.registerTool(
    "get_latest",
    {
      title: "Get latest Health Connect record",
      description: "Fetch the single most recent record of a given Health Connect record type.",
      inputSchema: {
        type: typeEnum,
      },
    },
    async ({ type }) => {
      const rows = await db
        .select()
        .from(healthRecords)
        .where(and(eq(healthRecords.recordType, type), isNull(healthRecords.deletedAt)))
        .orderBy(desc(healthRecords.startTime))
        .limit(1);

      const r = rows[0];
      const result = r
        ? {
            id: r.id,
            startTime: r.startTime.toISOString(),
            endTime: r.endTime ? r.endTime.toISOString() : null,
            zoneOffset: r.zoneOffset,
            sourceApp: r.sourceApp,
            data: r.data,
          }
        : null;

      return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
    },
  );

  server.registerTool(
    "get_daily_summary",
    {
      title: "Get daily health summary",
      description:
        "Aggregated summary for a single calendar day (Australia/Sydney timezone): steps, distance, calories, floors climbed, hydration, sleep duration, resting heart rate, exercise sessions, and latest weight as of that day.",
      inputSchema: {
        date: dateString,
      },
    },
    async ({ date }) => {
      const summary = await getDailySummary(date);
      return { content: [{ type: "text", text: JSON.stringify(summary, null, 2) }] };
    },
  );

  registerWriteTools(server);
}

/**
 * Write tools. Reaching these already requires the owner's MCP OAuth session (withMcpAuth),
 * so these are single-owner by construction. On top of that gate, every write is validated
 * against a strict allowlist with physiological bounds (see write-types.ts) before it is
 * queued — record types not on the allowlist can never be written. Writes are asynchronous:
 * the tool enqueues a row that the phone applies to Health Connect on its next sync.
 */
function registerWriteTools(server: McpServer) {
  const writableEnum = z.enum(WRITABLE_TYPE_WIRE_NAMES);
  const isoInstant = z
    .string()
    .datetime({ offset: true })
    .describe("ISO-8601 instant with offset, e.g. 2026-07-06T08:00:00+10:00");

  server.registerTool(
    "list_writable_data_types",
    {
      title: "List writable Health Connect data types",
      description:
        "Lists the Health Connect record types this connector is allowed to WRITE, with the exact " +
        "data fields, units, and accepted value ranges for each. Only these types can be written; " +
        "all other types are read-only. Consult this before calling write_record.",
      inputSchema: {},
    },
    async () => {
      const result = WRITABLE_TYPES.map((t) => ({
        type: t.wireName,
        shape: t.shape,
        endTimeRequired: t.shape === "interval",
        description: t.description,
      }));
      return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
    },
  );

  server.registerTool(
    "write_record",
    {
      title: "Write a Health Connect record",
      description:
        "Queue a single record to be written into Health Connect on the phone. Only record types " +
        "returned by list_writable_data_types are accepted, and values must fall within the ranges " +
        "listed there. The write is applied on the phone's next sync (typically within ~15 minutes), " +
        "not immediately; it will then appear in the read tools. Interval types (e.g. StepsRecord, " +
        "HydrationRecord, NutritionRecord) require endTime.",
      inputSchema: {
        type: writableEnum,
        startTime: isoInstant,
        endTime: isoInstant
          .optional()
          .describe("Required for interval record types; omit for instantaneous ones."),
        zoneOffset: z
          .string()
          .regex(/^[+-]\d{2}:\d{2}$/)
          .optional()
          .describe("Optional zone offset like +10:00; defaults to the time's own offset."),
        data: z
          .record(z.string(), z.unknown())
          .describe("Type-specific fields; see list_writable_data_types for names/units/ranges."),
      },
    },
    async ({ type, startTime, endTime, zoneOffset, data }) => {
      const validated = validateWrite({ type, startTime, endTime, zoneOffset, data });
      if (!validated.ok) {
        return {
          isError: true,
          content: [{ type: "text", text: `Rejected: ${validated.error}` }],
        };
      }

      const id = randomUUID();
      await db.insert(pendingWrites).values({
        id,
        recordType: validated.value.type,
        startTime: new Date(validated.value.startTime),
        endTime: validated.value.endTime ? new Date(validated.value.endTime) : null,
        zoneOffset: validated.value.zoneOffset,
        data: validated.value.data,
      });

      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                queued: true,
                id,
                type: validated.value.type,
                note: "Queued. Will be written to Health Connect on the phone's next sync, then readable via the read tools.",
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
    "list_pending_writes",
    {
      title: "List queued Health Connect writes",
      description:
        "Shows recent write_record requests and their status (pending = not yet applied on the " +
        "phone, applied = written to Health Connect, failed = rejected by the phone).",
      inputSchema: {
        status: z.enum(["pending", "applied", "failed"]).optional(),
        limit: z.number().int().min(1).max(200).default(50),
      },
    },
    async ({ status, limit }) => {
      const rows = await db
        .select()
        .from(pendingWrites)
        .where(status ? eq(pendingWrites.status, status) : undefined)
        .orderBy(desc(pendingWrites.createdAt))
        .limit(limit);

      const result = rows.map((r) => ({
        id: r.id,
        type: r.recordType,
        status: r.status,
        startTime: r.startTime.toISOString(),
        endTime: r.endTime ? r.endTime.toISOString() : null,
        data: r.data,
        error: r.error,
        createdAt: r.createdAt.toISOString(),
        appliedAt: r.appliedAt ? r.appliedAt.toISOString() : null,
      }));

      return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
    },
  );
}
