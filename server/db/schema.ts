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
