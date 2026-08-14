import { useState } from "react";
import { useParams } from "react-router-dom";
import { errorMessage, guestClient } from "../api/client";
import type { SearchResponse } from "../api/types";
import { StatusPill } from "../components/StatusPill";
import { FieldsTable } from "../components/FieldsTable";
import { BackButton } from "../components/BackButton";

export function GuestSearchPage() {
  const { token } = useParams();
  const [q, setQ] = useState("");
  const [result, setResult] = useState<SearchResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function runSearch() {
    if (!token) return;
    setError(null);
    try {
      const { data } = await guestClient(token).get<SearchResponse>("/guest/search", { params: { q: q || undefined } });
      setResult(data);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <div className="main" style={{ maxWidth: 700, margin: "0 auto" }}>
      <BackButton fallback="/" />
      <h1>Search shared documents</h1>
      {error && <div className="error-banner">{error}</div>}
      <div className="card">
        <div style={{ display: "flex", gap: 8 }}>
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search text..." style={{ flex: 1 }} />
          <button onClick={runSearch}>Search</button>
        </div>
      </div>
      {result?.items.map((item) => (
        <div className="card" key={item.documentId}>
          <p>
            <strong>{item.filename}</strong> <StatusPill status={item.status} />
          </p>
          {item.fields && <FieldsTable fields={item.fields} />}
        </div>
      ))}
    </div>
  );
}
