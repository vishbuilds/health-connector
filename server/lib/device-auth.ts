import { timingSafeEqual } from "crypto";
import type { NextRequest } from "next/server";

/**
 * Shared bearer-token check for the device-facing endpoints (/api/ingest, /api/writes,
 * /api/writes/ack). The phone authenticates with the `INGEST_SECRET` shared secret. The
 * comparison is constant-time to avoid leaking the secret via timing.
 *
 * This is intentionally separate from the MCP OAuth auth used by Claude: the phone and Claude
 * are different principals with different credentials.
 */
export function isDeviceAuthorized(request: NextRequest): boolean {
  const secret = process.env.INGEST_SECRET;
  if (!secret) return false;

  const header = request.headers.get("authorization") ?? "";
  const prefix = "Bearer ";
  if (!header.startsWith(prefix)) return false;
  return secretMatches(header.slice(prefix.length), secret);
}

/** Constant-time string comparison for shared secrets. */
export function secretMatches(candidate: string, secret: string): boolean {
  const candidateBuf = Buffer.from(candidate);
  const secretBuf = Buffer.from(secret);
  if (candidateBuf.length !== secretBuf.length) return false;
  return timingSafeEqual(candidateBuf, secretBuf);
}
