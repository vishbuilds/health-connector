# health-connector

Exposes Android [Health Connect](https://developer.android.com/health-and-fitness/health-connect) data (steps, sleep, heart rate, exercise, nutrition, cycle tracking, body measurements, vitals — every record type Health Connect stores) to Claude as an [MCP](https://modelcontextprotocol.io) Custom Connector, from any Claude client (web, mobile, desktop). Reads cover every record type; a small, deliberately curated subset can also be **written** back (see [Writing data](#writing-data)).

Health Connect has no cloud API — data only exists on the phone. This project bridges it: an Android app reads Health Connect data and syncs it to a small backend, which also serves the MCP endpoint Claude connects to.

```
phone (Health Connect) -> android/ app -> HTTPS POST -> server/ (/api/ingest) -> Postgres
                                                                       |
                                                            server/ (/api/mcp) <- Claude (OAuth via Better Auth)
```

## Repo layout

- `android/` — Kotlin/Compose app. Requests read permission for every Health Connect record type, does a one-time full-history backfill, then syncs incrementally every 15 minutes via Health Connect's changes-token API. Posts batches to the backend over HTTPS with a bearer secret.
- `server/` — Next.js app deployed to Vercel. `/api/ingest` receives data from the phone; `/api/mcp` is the MCP server Claude connects to, authenticated via [Better Auth](https://better-auth.com)'s MCP plugin (OAuth 2.1, single owner account).
- `.github/workflows/android-debug-apk.yml` — builds a debug APK on every push to `android/**`. This is the primary way to get an installable APK without running Android Studio yourself; download the `health-connector-debug-apk` artifact from the workflow run.

## Server setup

1. **Database**: create a Postgres database — either the Neon integration from Vercel's Marketplace, or [Supabase](https://supabase.com) in the `ap-southeast-2` (Sydney) region if you want firmer AU data residency than Neon offers. Either works with the same schema.
2. **Env vars** (see `server/.env.example`):
   - `DATABASE_URL`
   - `INGEST_SECRET` — random secret (`openssl rand -base64 32`); the Android app sends this as a bearer token.
   - `BETTER_AUTH_SECRET` — random secret (`openssl rand -base64 32`).
   - `BETTER_AUTH_URL` — the deployed URL (e.g. `https://health-connector.vercel.app`), or `http://localhost:3000` for local dev.
   - `OWNER_EMAIL` — the only email allowed to sign in.
3. **Push the schema**: `cd server && npm install && npm run db:push`. (Re-run this after pulling the write-support change — it adds the `pending_writes` table.)
4. **Seed the one allowed account** (there's no public sign-up route by design): `OWNER_PASSWORD=<your password> npm run auth:seed-user`.
5. **Deploy** to Vercel, rooted at `server/`. `vercel.json` pins functions to `syd1` (Sydney).
6. If you ever change `server/lib/auth.ts`'s plugin config, regenerate the auth schema with `npm run auth:generate-schema` and re-run `db:push`.

### Local development

```
cd server
npm install
cp .env.example .env.local   # fill in a local Postgres URL, e.g. postgres://user:pass@localhost:5432/health_connector_dev
npm run db:push
OWNER_PASSWORD=... npm run auth:seed-user
npm run dev
```

`db/client.ts` automatically uses `node-postgres` for a `localhost`/`127.0.0.1` `DATABASE_URL` and the Neon serverless driver otherwise, so local dev doesn't need a Neon account.

## Android app setup

The sandbox this project was built in has no Android SDK, so the debug APK is built in CI:

1. Push a commit touching `android/**` (or trigger `android-debug-apk.yml` manually via `workflow_dispatch`).
2. Download the `health-connector-debug-apk` artifact from the workflow run and sideload it onto your phone (enable "install unknown apps" for whichever app you transfer it through).
3. Open the app, grant all requested Health Connect permissions, then go to Settings and enter your deployed backend URL and `INGEST_SECRET`.
4. Backfill starts automatically once permissions are granted; use "Sync now" on the status screen to trigger it immediately.

To build/run from Android Studio instead: open `android/`, let Gradle sync (it will offer to regenerate `gradle/wrapper/gradle-wrapper.jar`, which isn't checked in — see `android/gradle/wrapper/WRAPPER_JAR_NOTE.txt`), then run on a device with Health Connect installed.

## Connecting Claude

In Claude, add a Custom Connector pointing at `https://<your-deployment>.vercel.app/api/mcp`. Claude will redirect you to sign in with the owner account seeded above; after that one-time login, Claude can call:

- `list_data_types` — every record type synced so far, with counts and date ranges.
- `get_records` — raw records of one type within a date range (Australia/Sydney calendar dates).
- `get_latest` — the most recent record of a given type.
- `get_daily_summary` — steps, distance, calories, floors, hydration, sleep duration, resting heart rate, exercise sessions, and latest weight for one Sydney-local calendar day.
- `list_writable_data_types` — the record types that can be written, with each type's fields, units, and accepted value ranges.
- `write_record` — queue a single record to be written into Health Connect on the phone.
- `list_pending_writes` — recent write requests and their status (pending / applied / failed).

## Writing data

Health Connect only exists on the phone, and the phone is only reachable via its own outbound polling, so writes use a queued reverse channel rather than a direct call:

```
Claude --(write_record, MCP/OAuth)--> server: INSERT pending_writes (status=pending)
phone --(GET /api/writes, INGEST_SECRET)--> drains queue -> HealthConnectClient.insertRecords
phone --(POST /api/writes/ack)--> server marks applied/failed
```

A queued write is applied on the phone's **next sync** (within ~15 minutes, or immediately via "Sync now"), after which it flows back through the normal read sync and shows up in the read tools. `list_pending_writes` shows where each request is in that lifecycle.

Security model:

- **Owner-only.** `write_record` is reachable only through the MCP OAuth session, i.e. the single seeded owner account — the same gate as every read tool.
- **Strict allowlist.** Only the record types in `server/lib/write-types.ts` (mirrored in `android/.../WritableRecordTypes.kt`) can ever be written; every other type is read-only. The current set is weight, height, body fat, body temperature, blood pressure, blood glucose, oxygen saturation, resting heart rate, respiratory rate, hydration, steps, and nutrition.
- **Bounded validation.** Each type has a strict schema with physiologically sane ranges; out-of-range or malformed values are rejected before they're queued, and future-dated timestamps are refused.
- **Idempotent.** Each queued write's server id is used as the Health Connect `clientRecordId`, so a redelivered write updates the same record instead of duplicating it.
- **Device auth.** `/api/writes` and `/api/writes/ack` require the same timing-safe `INGEST_SECRET` bearer as `/api/ingest`.
- **Revocable on-device.** Write access is per-type in Health Connect; declining a WRITE permission on the Permissions screen disables writing that type without affecting reads. Records written by this app are attributed to it (manual entry) and can be deleted from Health Connect like any other source.
