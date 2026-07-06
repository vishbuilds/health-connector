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

// One driver is chosen per host; the other handle stays null. Both are retained so
// `runReadOnlyQuery` can drive whichever one is active for the ad-hoc SQL query tool.
const neonClient = isNeonHost(databaseUrl) ? neon(databaseUrl) : null;
const pgPool = isNeonHost(databaseUrl)
  ? null
  : new Pool({
      connectionString: databaseUrl,
      // Supabase (and other managed Postgres) require TLS; their pooler certs aren't
      // always in Node's default trust store, so don't hard-fail on the chain. Local
      // Postgres connects without TLS.
      ssl: isLocalDatabaseUrl(databaseUrl) ? undefined : { rejectUnauthorized: false },
      // Supabase's session pooler caps total client connections (pool_size 15). Keep each
      // instance's pool small enough that concurrent instances don't exhaust the shared
      // cap. Local Postgres has no such limit but a small pool is harmless there too.
      max: isLocalDatabaseUrl(databaseUrl) ? 10 : 4,
    });

export const db = neonClient ? drizzleNeon(neonClient, { schema }) : drizzlePg(pgPool!, { schema });

/** Ad-hoc read limits, surfaced to the model in the query tool's description. */
export const READ_ONLY_LIMITS = { statementTimeoutMs: 10_000, maxRows: 1_000 } as const;

/**
 * Executes a single ad-hoc SELECT for the `query_health_data` MCP tool. The security boundary
 * is NOT the caller's SQL string — it's that the statement runs inside a **READ ONLY** Postgres
 * transaction with a `statement_timeout`. READ ONLY is what makes this safe even against a
 * data-modifying CTE (`WITH t AS (DELETE ... RETURNING *) ...`) or `SELECT ... INTO`: Postgres
 * rejects any write at execution time, so no lightweight string guard has to be perfect. The
 * transaction is always rolled back — a SELECT has nothing to commit.
 */
export async function runReadOnlyQuery(text: string): Promise<Record<string, unknown>[]> {
  if (neonClient) {
    const results = await neonClient.transaction(
      (txn) => [
        txn.query(`SET LOCAL statement_timeout = ${READ_ONLY_LIMITS.statementTimeoutMs}`),
        txn.query(text),
      ],
      { readOnly: true },
    );
    return results[1] as Record<string, unknown>[];
  }

  const client = await pgPool!.connect();
  try {
    await client.query("BEGIN");
    await client.query("SET TRANSACTION READ ONLY");
    await client.query(`SET LOCAL statement_timeout = ${READ_ONLY_LIMITS.statementTimeoutMs}`);
    const res = await client.query(text);
    return res.rows as Record<string, unknown>[];
  } finally {
    try {
      await client.query("ROLLBACK");
    } catch {
      /* connection may already be aborted; release regardless */
    }
    client.release();
  }
}
