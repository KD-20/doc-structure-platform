import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { apiClient, errorMessage } from "../api/client";
import type { SearchFilter, SearchResponse, SearchResultItem } from "../api/types";
import { StatusPill } from "../components/StatusPill";
import { DocumentPreview } from "../components/DocumentPreview";
import { FieldSelect } from "../components/FieldSelect";
import { DownloadIcon } from "../components/icons";

// The API already returns items ordered by match score descending (full-text rank, fuzzy/semantic
// similarity, or a 50/50 blend of both when both are given — see SearchService#searchImpl's
// rankExpr) — items.map below renders that order as-is rather than re-bucketing by status, which
// used to hide it (a lower-scored STRUCTURED result could out-rank a closer semantic match).
// matchPercent turns the raw score into something a user can actually see and trust the ordering
// against, capped at 100% since a blended score can exceed 1.0 in edge cases.
function matchPercent(textRank: number): number {
  return Math.min(100, Math.round(textRank * 100));
}

export function SearchPage() {
  const { tenantId } = useParams();
  const [q, setQ] = useState("");
  const [docType, setDocType] = useState("");
  const [semanticQuery, setSemanticQuery] = useState("");
  const [filters, setFilters] = useState<SearchFilter[]>([]);
  const [result, setResult] = useState<SearchResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  // Doc types actually present in this tenant's documents, offered as the full <select> list.
  const [docTypeSuggestions, setDocTypeSuggestions] = useState<string[]>([]);

  useEffect(() => {
    apiClient
      .get<string[]>(`/tenants/${tenantId}/search/doc-types`)
      .then((res) => setDocTypeSuggestions(res.data))
      .catch(() => setDocTypeSuggestions([]));
  }, [tenantId]);

  function addFilter() {
    // "contains" default, not "eq": most extracted fields hold free text (an address block, a
    // degree line, a work-history block), where an exact whole-value match almost never applies
    // — "eq" is for the minority case (an ID, an amount) and stays available in the dropdown.
    setFilters([...filters, { field: "", op: "contains", value: "" }]);
  }

  function updateFilter(i: number, patch: Partial<SearchFilter>) {
    setFilters(filters.map((f, idx) => (idx === i ? { ...f, ...patch } : f)));
  }

  function removeFilter(i: number) {
    setFilters(filters.filter((_, idx) => idx !== i));
  }

  async function handleDownload(item: SearchResultItem) {
    setError(null);
    try {
      // Authenticated blob fetch, not a plain <a href> — same technique as the document
      // detail page's download button (the JWT lives in-memory, not a cookie).
      const res = await apiClient.get(`/tenants/${tenantId}/documents/${item.documentId}/download`, {
        responseType: "blob",
      });
      const url = URL.createObjectURL(res.data as Blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = item.filename;
      link.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  // Takes an optional docType override so selecting a new type can search immediately with
  // it, rather than firing one request against the about-to-be-stale state value and a
  // second once the state catches up.
  async function runSearch(docTypeOverride?: string) {
    setError(null);
    setLoading(true);
    try {
      const activeFilters = filters.filter((f) => f.field && f.value);
      const { data } = await apiClient.get<SearchResponse>(`/tenants/${tenantId}/search`, {
        params: {
          q: q || undefined,
          docType: (docTypeOverride ?? docType) || undefined,
          semanticQuery: semanticQuery || undefined,
          filters: activeFilters.length > 0 ? JSON.stringify(activeFilters) : undefined,
        },
      });
      setResult(data);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="search-page">
      <h1>Search</h1>
      {error && <div className="error-banner">{error}</div>}

      <div className="card">
        <div className="form-row">
          <label>Full-text query</label>
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="e.g. Contoso" />
        </div>
        <div className="form-row" style={{ maxWidth: 240 }}>
          <label>Document type</label>
          <select
            value={docType}
            onChange={(e) => {
              const value = e.target.value;
              setDocType(value);
              runSearch(value);
            }}
          >
            <option value="">All types</option>
            {docTypeSuggestions.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </div>

        <label style={{ fontSize: 13, color: "var(--muted)", display: "block", marginBottom: 10 }}>
          Structured filters (pick "Any field" if you don't know which field holds the value)
        </label>
        {filters.map((f, i) => (
          <div key={i} style={{ display: "flex", gap: 8, marginBottom: 8 }}>
            <FieldSelect
              tenantId={tenantId!}
              docType={docType}
              value={f.field}
              onChange={(value) => updateFilter(i, { field: value })}
            />
            <select value={f.op} onChange={(e) => updateFilter(i, { op: e.target.value as SearchFilter["op"] })}>
              <option value="eq">equals (exact)</option>
              <option value="contains">contains</option>
              <option value="fuzzy">fuzzy (close match, typos/case ok)</option>
              <option value="gt">greater than</option>
              <option value="gte">greater than or equal</option>
              <option value="lt">less than</option>
              <option value="lte">less than or equal</option>
            </select>
            <input
              placeholder={["gt", "gte", "lt", "lte"].includes(f.op) ? "number" : "value"}
              value={f.value}
              onChange={(e) => updateFilter(i, { value: e.target.value })}
            />
            <button className="danger icon-btn" type="button" onClick={() => removeFilter(i)}>
              ×
            </button>
          </div>
        ))}
        <button className="secondary small" type="button" onClick={addFilter} style={{ marginTop: 4 }}>
          + Add filter
        </button>

        <div className="form-row" style={{ marginTop: 16 }}>
          <label>
            Fuzzy / semantic query (finds conceptually similar documents, not just keyword
            matches — only returns results above ~70% similarity; requires an embedding provider
            to be configured)
          </label>
          <input value={semanticQuery} onChange={(e) => setSemanticQuery(e.target.value)} placeholder="optional" />
        </div>

        <button onClick={() => runSearch()} disabled={loading}>
          {loading ? "Searching..." : "Search"}
        </button>
      </div>

      {result && (
        <div className="card">
          <p className="muted">
            {result.totalElements} result(s).
            {result.semanticQueryProvided && !result.semanticSearchAvailable && (
              <>
                {" "}
                Semantic search isn't enabled on this deployment yet — matched your semantic query as literal text
                instead.
              </>
            )}
          </p>
          <div className="item-bar-list">
            {result.items.map((item) => (
              <div className="item-bar static" key={item.documentId}>
                <DocumentPreview
                  tenantId={tenantId!}
                  documentId={item.documentId}
                  contentType={item.contentType}
                  filename={item.filename}
                  size="row"
                >
                  <div className="item-bar-main">
                    <span className="item-bar-title">{item.filename}</span>
                    <div className="item-bar-meta">
                      {item.docType} · <StatusPill status={item.status} />
                    </div>
                  </div>
                </DocumentPreview>
                <div className="item-bar-right" style={{ display: "flex", alignItems: "center", gap: 10 }}>
                  <span className="muted" style={{ fontSize: 12 }} title="Match score against your query">
                    {matchPercent(item.textRank)}% match
                  </span>
                  <button
                    className="icon-btn secondary"
                    title="Download original"
                    onClick={() => handleDownload(item)}
                  >
                    <DownloadIcon />
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
