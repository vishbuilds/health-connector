import { bigserial, index, integer, jsonb, pgTable, text, timestamp } from "drizzle-orm/pg-core";

export const healthRecords = pgTable(
  "health_records",
  {
    id: text("id").primaryKey(), // Health Connect metadata.id
    recordType: text("record_type").notNull(), // e.g. "StepsRecord"
    startTime: timestamp("start_time", { withTimezone: true }).notNull(),
    endTime: timestamp("end_time", { withTimezone: true }), // null for instantaneous records
    zoneOffset: text("zone_offset"), // e.g. "+10:00"
    data: jsonb("data").notNull(), // full type-specific payload
    sourceApp: text("source_app"), // metadata.dataOrigin packageName
    deviceId: text("device_id"),
    syncedAt: timestamp("synced_at", { withTimezone: true }).notNull().defaultNow(),
    deletedAt: timestamp("deleted_at", { withTimezone: true }),
  },
  (table) => [
    index("idx_health_records_type_time").on(table.recordType, table.startTime),
    index("idx_health_records_start_time").on(table.startTime),
  ],
);

/**
 * Queue of records Claude has asked to WRITE into Health Connect. Rows are created by the
 * `write_record` MCP tool (server side) and drained by the phone: it polls GET /api/writes
 * for `pending` rows, applies them to Health Connect, then POSTs /api/writes/ack to move
 * each row to `applied` or `failed`. The row `id` is used verbatim as the Health Connect
 * clientRecordId so re-delivery is idempotent (a retried write updates the same record).
 */
export const pendingWrites = pgTable(
  "pending_writes",
  {
    id: text("id").primaryKey(), // server-generated UUID; becomes Health Connect clientRecordId
    recordType: text("record_type").notNull(), // e.g. "WeightRecord" (must be in the write allowlist)
    startTime: timestamp("start_time", { withTimezone: true }).notNull(),
    endTime: timestamp("end_time", { withTimezone: true }), // required for interval types, else null
    zoneOffset: text("zone_offset"), // e.g. "+10:00", optional
    data: jsonb("data").notNull(), // validated type-specific payload
    status: text("status").notNull().default("pending"), // pending | applied | failed
    error: text("error"), // failure reason reported by the phone, if status = failed
    healthConnectId: text("health_connect_id"), // HC metadata.id assigned on successful insert
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    appliedAt: timestamp("applied_at", { withTimezone: true }),
  },
  (table) => [index("idx_pending_writes_status").on(table.status)],
);

export const ingestLog = pgTable("ingest_log", {
  id: bigserial("id", { mode: "number" }).primaryKey(),
  deviceId: text("device_id"),
  receivedAt: timestamp("received_at", { withTimezone: true }).notNull().defaultNow(),
  upsertCount: integer("upsert_count").notNull().default(0),
  deletionCount: integer("deletion_count").notNull().default(0),
  recordTypes: jsonb("record_types"),
});

export type HealthRecordRow = typeof healthRecords.$inferSelect;
export type HealthRecordInsert = typeof healthRecords.$inferInsert;
export type PendingWriteRow = typeof pendingWrites.$inferSelect;
export type PendingWriteInsert = typeof pendingWrites.$inferInsert;
