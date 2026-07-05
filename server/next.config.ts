import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  // Better Auth serves its OAuth discovery documents under /api/auth/.well-known/*,
  // but RFC 8414 clients (e.g. Claude's MCP connector) look for the authorization
  // server metadata at the issuer root — https://<host>/.well-known/oauth-authorization-server.
  // Bridge the root well-known paths to Better Auth's handler so dynamic client
  // registration can be discovered.
  async rewrites() {
    return [
      {
        source: "/.well-known/oauth-authorization-server",
        destination: "/api/well-known/oauth-authorization-server",
      },
      {
        source: "/.well-known/oauth-protected-resource",
        destination: "/api/well-known/oauth-protected-resource",
      },
    ];
  },
};

export default nextConfig;
