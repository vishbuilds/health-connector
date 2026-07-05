import { APIError, betterAuth } from "better-auth";
import { drizzleAdapter } from "better-auth/adapters/drizzle";
import { mcp } from "better-auth/plugins";
import { nextCookies } from "better-auth/next-js";
import { db } from "@/db/client";
import * as schema from "@/db/auth-schema";

const ownerEmail = process.env.OWNER_EMAIL;

export const auth = betterAuth({
  baseURL: process.env.BETTER_AUTH_URL,
  secret: process.env.BETTER_AUTH_SECRET,
  database: drizzleAdapter(db, {
    provider: "pg",
    schema,
  }),
  emailAndPassword: {
    enabled: true,
  },
  databaseHooks: {
    user: {
      create: {
        before: async (user) => {
          if (!ownerEmail || user.email !== ownerEmail) {
            throw new APIError("BAD_REQUEST", {
              message: "Sign-up is disabled. This server has a single owner account.",
            });
          }
          return { data: user };
        },
      },
    },
  },
  plugins: [
    mcp({
      loginPage: "/login",
    }),
    nextCookies(),
  ],
});
