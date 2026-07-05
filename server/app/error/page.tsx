"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";

function ErrorDetails() {
  const params = useSearchParams();
  return (
    <>
      <p>
        <strong>{params.get("error") ?? "unknown_error"}</strong>
      </p>
      {params.get("error_description") && <p>{params.get("error_description")}</p>}
    </>
  );
}

export default function OAuthErrorPage() {
  return (
    <main>
      <h1>Authorization error</h1>
      <Suspense fallback={null}>
        <ErrorDetails />
      </Suspense>
    </main>
  );
}
