import { asc, eq } from "drizzle-orm";
import { NextRequest, NextResponse } from "next/server";
import { db } from "@/db/client";
import { pendingWrites } from "@/db/schema";
import { isDeviceAuthorized } from "@/lib/device-auth";

/**
 * Device-facing: the phone polls this to drain the write queue. Returns up to `MAX_BATCH`
 * `pending` writes, oldest first, in the same wire shape the Android RecordWriter consumes.
 * Authenticated with the INGEST_SECRET bearer (same principal as /api/ingest).
 *
 * Rows stay `pending` until the phone acks them via POST /api/writes/ack, so a poll that the
 * phone fails to fully process just re-returns the same rows next time (idempotent on the phone
 * via clientRecordId = row id).
 */
const MAX_BATCH = 100;

export async function GET(request: NextRequest) {
  if (!isDeviceAuthorized(request)) {
    return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  }

  const rows = await db
    .select()
    .from(pendingWrites)
    .where(eq(pendingWrites.status, "pending"))
    .orderBy(asc(pendingWrites.createdAt))
    .limit(MAX_BATCH);

  const writes = rows.map((r) => ({
    id: r.id,
    recordType: r.recordType,
    startTime: r.startTime.toISOString(),
    endTime: r.endTime ? r.endTime.toISOString() : null,
    zoneOffset: r.zoneOffset,
    data: r.data,
  }));

  return NextResponse.json({ writes });
}

// Guard against accidental POSTs to this path (acks live at /api/writes/ack).
export async function POST() {
  return NextResponse.json({ error: "use /api/writes/ack" }, { status: 405 });
}
