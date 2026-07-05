import { and, count, desc, eq, gte, isNull, lt, max, min } from "drizzle-orm";
import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { db } from "@/db/client";
import { healthRecords } from "@/db/schema";
import { RECORD_TYPES, RECORD_TYPE_WIRE_NAMES } from "./record-types";
import { getDailySummary, localDateToUtc } from "./daily-summary";

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
}
