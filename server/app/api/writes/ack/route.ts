import { and, eq, sql } from "drizzle-orm";
import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { db } from "@/db/client";
import { pendingWrites } from "@/db/schema";
import { isDeviceAuthorized } from "@/lib/device-auth";

/**
 * Device-facing: the phone reports the outcome of writes it drained from GET /api/writes.
 * `applied` entries carry the Health Connect metadata.id assigned on insert; `failed` entries
 * carry an error string. Only rows currently `pending` are transitioned, so a duplicate ack is
 * a no-op. Authenticated with the INGEST_SECRET bearer.
 */
const ackSchema = z.object({
  applied: z
    .array(z.object({ id: z.string().min(1), healthConnectId: z.string().min(1).optional() }))
    .max(500)
    .default([]),
  failed: z
    .array(z.object({ id: z.string().min(1), error: z.string().max(1000).optional() }))
    .max(500)
    .default([]),
});

export async function POST(request: NextRequest) {
  if (!isDeviceAuthorized(request)) {
    return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  }

  const json = await request.json().catch(() => null);
  const parsed = ackSchema.safeParse(json);
  if (!parsed.success) {
    return NextResponse.json({ error: "invalid body", issues: parsed.error.issues }, { status: 400 });
  }

  const { applied, failed } = parsed.data;

  // Updates are run sequentially rather than in a transaction: each one is independent and
  // guarded by `status = 'pending'`, so a partial apply just leaves the remainder to be re-acked
  // next drain. (The Neon HTTP driver this project can run on has no interactive transactions.)
  for (const a of applied) {
    await db
      .update(pendingWrites)
      .set({
        status: "applied",
        appliedAt: sql`now()`,
        healthConnectId: a.healthConnectId ?? null,
        error: null,
      })
      .where(and(eq(pendingWrites.id, a.id), eq(pendingWrites.status, "pending")));
  }
  for (const f of failed) {
    await db
      .update(pendingWrites)
      .set({ status: "failed", error: f.error ?? "unknown error" })
      .where(and(eq(pendingWrites.id, f.id), eq(pendingWrites.status, "pending")));
  }

  return NextResponse.json({ applied: applied.length, failed: failed.length });
}
