import { oAuthProtectedResourceMetadata } from "better-auth/plugins";
import { auth } from "@/lib/auth";

// RFC 9728 protected-resource metadata, served at the root via a rewrite from
// /.well-known/oauth-protected-resource (see next.config.ts).
export const GET = oAuthProtectedResourceMetadata(auth);
