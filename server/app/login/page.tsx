"use client";

import { useEffect, useState } from "react";
import { authClient } from "@/lib/auth-client";

export default function LoginPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // Read after mount (not during render) so SSR and hydration agree on the link's href.
  const [search, setSearch] = useState("");
  useEffect(() => setSearch(window.location.search), []);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError(null);

    const { error: signInError } = await authClient.signIn.email({ email, password });

    if (signInError) {
      setError(signInError.message ?? "Sign-in failed");
      setSubmitting(false);
      return;
    }

    // Resume the MCP OAuth authorize flow with the original query params
    // (client_id, redirect_uri, response_type, scope, code_challenge, state, ...)
    // that Better Auth's mcp plugin forwarded onto this login page.
    const search = typeof window !== "undefined" ? window.location.search : "";
    window.location.href = `/api/auth/mcp/authorize${search}`;
  }

  return (
    <main style={{ maxWidth: 360 }}>
      <h1>Sign in</h1>
      <p>This server has a single owner account. Sign in to authorize this connector.</p>
      <form onSubmit={handleSubmit} style={{ display: "flex", flexDirection: "column", gap: "0.75rem" }}>
        <label>
          Email
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            style={{ display: "block", width: "100%" }}
          />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            style={{ display: "block", width: "100%" }}
          />
        </label>
        {error && <p style={{ color: "crimson" }}>{error}</p>}
        <button type="submit" disabled={submitting}>
          {submitting ? "Signing in..." : "Sign in"}
        </button>
      </form>
      <p>
        <a href={`/reset-password${search}`}>Forgot password?</a>
      </p>
    </main>
  );
}
