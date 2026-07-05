import { timingSafeEqual } from "crypto";
import { inArray, sql } from "drizzle-orm";
import { NextRequest, NextResponse } from "next/server";
import { db } from "@/db/client";
import { healthRecords, ingestLog } from "@/db/schema";
import { ingestRequestSchema } from "@/lib/ingest-schema";

function isAuthorized(request: NextRequest): boolean {
  const secret = process.env.INGEST_SECRET;
  if (!secret) return false;

  const header = request.headers.get("authorization") ?? "";
  const prefix = "Bearer ";
  if (!header.startsWith(prefix)) return false;
  const token = header.slice(prefix.length);

  const tokenBuf = Buffer.from(token);
  const secretBuf = Buffer.from(secret);
  if (tokenBuf.length !== secretBuf.length) return false;
  return timingSafeEqual(tokenBuf, secretBuf);
}

export async function POST(request: NextRequest) {
  if (!isAuthorized(request)) {
    return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  }

  const json = await request.json().catch(() => null);
  if (json === null) {
    return NextResponse.json({ error: "invalid json" }, { status: 400 });
  }

  const parsed = ingestRequestSchema.safeParse(json);
  if (!parsed.success) {
    return NextResponse.json({ error: "invalid body", issues: parsed.error.issues }, { status: 400 });
  }

  const { deviceId, upserts, deletions } = parsed.data;

  if (upserts.length > 0) {
    const rows = upserts.map((record) => ({
      id: record.id,
      recordType: record.recordType,
      startTime: new Date(record.startTime),
      endTime: record.endTime ? new Date(record.endTime) : null,
      zoneOffset: record.zoneOffset ?? null,
      data: record.data,
      sourceApp: record.sourceApp ?? null,
      deviceId,
    }));

    await db
      .insert(healthRecords)
      .values(rows)
      .onConflictDoUpdate({
        target: healthRecords.id,
        set: {
          recordType: sql`excluded.record_type`,
          startTime: sql`excluded.start_time`,
          endTime: sql`excluded.end_time`,
          zoneOffset: sql`excluded.zone_offset`,
          data: sql`excluded.data`,
          sourceApp: sql`excluded.source_app`,
          deviceId: sql`excluded.device_id`,
          syncedAt: sql`now()`,
          deletedAt: sql`null`,
        },
      });
  }

  if (deletions.length > 0) {
    await db
      .update(healthRecords)
      .set({ deletedAt: new Date() })
      .where(inArray(healthRecords.id, deletions));
  }

  const recordTypeCounts = upserts.reduce<Record<string, number>>((acc, r) => {
    acc[r.recordType] = (acc[r.recordType] ?? 0) + 1;
    return acc;
  }, {});

  await db.insert(ingestLog).values({
    deviceId,
    upsertCount: upserts.length,
    deletionCount: deletions.length,
    recordTypes: recordTypeCounts,
  });

  return NextResponse.json({ upserted: upserts.length, deleted: deletions.length });
}
