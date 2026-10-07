"use client";

import { useEffect, useState } from "react";

export default function ResetPasswordPage() {
  const [recoveryKey, setRecoveryKey] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  // Carry the MCP OAuth authorize params back to /login so the connector flow can resume.
  // Read after mount (not during render) so SSR and hydration agree on the links' hrefs.
  const [search, setSearch] = useState("");
  useEffect(() => setSearch(window.location.search), []);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (newPassword !== confirmPassword) {
      setError("Passwords don't match");
      return;
    }

    setSubmitting(true);
    const res = await fetch("/api/reset-password", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ recoveryKey, newPassword }),
    });
    setSubmitting(false);

    if (!res.ok) {
      const body = await res.json().catch(() => null);
      setError(body?.error ?? "Password reset failed");
      return;
    }
    setDone(true);
  }

  if (done) {
    return (
      <main style={{ maxWidth: 360 }}>
        <h1>Password reset</h1>
        <p>Your password has been changed and all existing sessions were signed out.</p>
        <a href={`/login${search}`}>Back to sign in</a>
      </main>
    );
  }

  return (
    <main style={{ maxWidth: 360 }}>
      <h1>Reset password</h1>
      <p>
        Enter this server&apos;s recovery key (the <code>INGEST_SECRET</code> environment variable — find it in
        your Vercel project settings or the Android app&apos;s settings) and choose a new password.
      </p>
      <form onSubmit={handleSubmit} style={{ display: "flex", flexDirection: "column", gap: "0.75rem" }}>
        <label>
          Recovery key
          <input
            type="password"
            value={recoveryKey}
            onChange={(e) => setRecoveryKey(e.target.value)}
            required
            autoComplete="off"
            style={{ display: "block", width: "100%" }}
          />
        </label>
        <label>
          New password
          <input
            type="password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            required
            autoComplete="new-password"
            style={{ display: "block", width: "100%" }}
          />
        </label>
        <label>
          Confirm new password
          <input
            type="password"
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
            required
            autoComplete="new-password"
            style={{ display: "block", width: "100%" }}
          />
        </label>
        {error && <p style={{ color: "crimson" }}>{error}</p>}
        <button type="submit" disabled={submitting}>
          {submitting ? "Resetting..." : "Reset password"}
        </button>
      </form>
      <p>
        <a href={`/login${search}`}>Back to sign in</a>
      </p>
    </main>
  );
}
