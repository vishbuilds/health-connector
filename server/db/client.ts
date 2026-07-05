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
 * Neon's serverless HTTP driver only speaks to Neon's endpoint proxy, so local
 * development against a plain Postgres (e.g. `postgres://localhost/...`) uses
 * node-postgres instead. Anything with a real host (Neon, Supabase, etc.) uses
 * the serverless driver, which is what Vercel's function runtime needs.
 */
function isLocalDatabaseUrl(url: string): boolean {
  const host = new URL(url).hostname;
  return host === "localhost" || host === "127.0.0.1";
}

const databaseUrl = getDatabaseUrl();

export const db = isLocalDatabaseUrl(databaseUrl)
  ? drizzlePg(new Pool({ connectionString: databaseUrl }), { schema })
  : drizzleNeon(neon(databaseUrl), { schema });
