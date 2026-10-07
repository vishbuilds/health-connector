import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { auth } from "@/lib/auth";
import { secretMatches } from "@/lib/device-auth";

const bodySchema = z.object({
  recoveryKey: z.string().min(1),
  newPassword: z.string(),
});

/**
 * POST /api/reset-password — sets a new password for the single owner account.
 *
 * There's no email provider configured, so instead of a mailed reset link the caller proves
 * ownership with the `INGEST_SECRET` (readable from the Vercel project's env vars or the
 * Android app's settings). It's a 32-byte random secret, so it isn't guessable. On success
 * every existing session is revoked so a leaked session can't outlive the reset.
 */
export async function POST(request: NextRequest) {
  const parsed = bodySchema.safeParse(await request.json().catch(() => null));
  if (!parsed.success) {
    return NextResponse.json({ error: "invalid request body" }, { status: 400 });
  }
  const { recoveryKey, newPassword } = parsed.data;

  const secret = process.env.INGEST_SECRET;
  const ownerEmail = process.env.OWNER_EMAIL;
  if (!secret || !ownerEmail) {
    return NextResponse.json({ error: "password reset is not configured on this server" }, { status: 500 });
  }
  if (!secretMatches(recoveryKey, secret)) {
    return NextResponse.json({ error: "invalid recovery key" }, { status: 401 });
  }

  const ctx = await auth.$context;
  const { minPasswordLength, maxPasswordLength } = ctx.password.config;
  if (newPassword.length < minPasswordLength || newPassword.length > maxPasswordLength) {
    return NextResponse.json(
      { error: `password must be ${minPasswordLength}–${maxPasswordLength} characters` },
      { status: 400 },
    );
  }

  const found = await ctx.internalAdapter.findUserByEmail(ownerEmail);
  if (!found) {
    return NextResponse.json(
      { error: "owner account not found — seed it with `npm run auth:seed-user`" },
      { status: 404 },
    );
  }

  await ctx.internalAdapter.updatePassword(found.user.id, await ctx.password.hash(newPassword));
  await ctx.internalAdapter.deleteUserSessions(found.user.id);

  return NextResponse.json({ ok: true });
}
