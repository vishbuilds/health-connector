import { createMcpHandler } from "mcp-handler";
import { withMcpAuth } from "better-auth/plugins";
import { auth } from "@/lib/auth";
import { registerHealthTools } from "@/lib/mcp-tools";

const mcpHandler = createMcpHandler(
  (server) => {
    registerHealthTools(server);
  },
  {
    serverInfo: { name: "health-connector", version: "0.1.0" },
  },
  {
    basePath: "/api",
    maxDuration: 60,
  },
);

const handler = withMcpAuth(auth, async (req) => mcpHandler(req));

export { handler as GET, handler as POST };
