import { z } from "zod";
import { RECORD_TYPE_WIRE_NAMES } from "./record-types";

export const ingestRecordSchema = z.object({
  id: z.string().min(1),
  recordType: z.enum(RECORD_TYPE_WIRE_NAMES),
  startTime: z.string().datetime({ offset: true }),
  endTime: z.string().datetime({ offset: true }).nullable().optional(),
  zoneOffset: z.string().nullable().optional(),
  sourceApp: z.string().nullable().optional(),
  data: z.record(z.string(), z.unknown()),
});

export const ingestRequestSchema = z.object({
  deviceId: z.string().min(1),
  upserts: z.array(ingestRecordSchema).max(5000).default([]),
  deletions: z.array(z.string().min(1)).max(5000).default([]),
});

export type IngestRequest = z.infer<typeof ingestRequestSchema>;
