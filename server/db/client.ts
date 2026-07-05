import { neon } from "@neondatabase/serverless";
import { drizzle as drizzleNeon } from "drizzle-orm/neon-http";
import { drizzle as drizzlePg } from "drizzle-orm/node-postgres";
import { Pool } from "pg";
import * as healthSchema from "./schema";
import * as authSchema from "./auth-schema";

const schema = { ...healthSchema, ...authSchema };

function getDatabaseUrl(): string {
  const url = process.env.DATABASE_URL;
  if (!url) {
    throw new Error("DATABASE_URL is not set");
  }
  return url;
}

/**
 * Neon's serverless HTTP driver only speaks Neon's own endpoint protocol, so it
 * is used exclusively for Neon hosts. Everything else — Supabase's Postgres or a
 * plain local Postgres (e.g. `postgres://localhost/...`) — goes through
 * node-postgres, which speaks the standard wire protocol.
 */
function isNeonHost(url: string): boolean {
  return new URL(url).hostname.endsWith(".neon.tech");
}

function isLocalDatabaseUrl(url: string): boolean {
  const host = new URL(url).hostname;
  return host === "localhost" || host === "127.0.0.1";
}

const databaseUrl = getDatabaseUrl();

export const db = isNeonHost(databaseUrl)
  ? drizzleNeon(neon(databaseUrl), { schema })
  : drizzlePg(
      new Pool({
        connectionString: databaseUrl,
        // Supabase (and other managed Postgres) require TLS; their pooler certs
        // aren't always in Node's default trust store, so don't hard-fail on the
        // chain. Local Postgres connects without TLS.
        ssl: isLocalDatabaseUrl(databaseUrl)
          ? undefined
          : { rejectUnauthorized: false },
      }),
      { schema },
    );
