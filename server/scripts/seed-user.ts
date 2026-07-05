/**
 * One-off setup script: creates the single owner account that's allowed to
 * sign in (databaseHooks.user.create.before in lib/auth.ts rejects any other
 * email). Run once after the database is migrated:
 *
 *   OWNER_PASSWORD=<password> npm run auth:seed-user
 */
import { auth } from "@/lib/auth";

async function main() {
  const email = process.env.OWNER_EMAIL;
  const password = process.env.OWNER_PASSWORD;

  if (!email) throw new Error("OWNER_EMAIL is not set");
  if (!password) throw new Error("OWNER_PASSWORD is not set (pass it inline, don't commit it)");

  await auth.api.signUpEmail({
    body: { email, password, name: "Owner" },
  });

  console.log(`Created owner account for ${email}`);
}

main()
  .then(() => process.exit(0))
  .catch((err) => {
    console.error(err);
    process.exit(1);
  });
