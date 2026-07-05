import { oAuthDiscoveryMetadata } from "better-auth/plugins";
import { auth } from "@/lib/auth";

// RFC 8414 authorization-server metadata, served at the issuer root via a
// rewrite from /.well-known/oauth-authorization-server (see next.config.ts).
// Unlike Better Auth's catch-all, this helper builds the document from the auth
// config independent of the request path, so the rewrite works.
export const GET = oAuthDiscoveryMetadata(auth);
