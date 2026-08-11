import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { apiClient, errorMessage } from "../api/client";
import type { DocumentStatus, SearchFilter, SearchResponse, SearchResultItem } from "../api/types";
import { StatusPill } from "../components/StatusPill";
import { DocumentPreview } from "../components/DocumentPreview";
import { FieldSelect } from "../components/FieldSelect";
import { DownloadIcon } from "../components/icons";

// Best outcome first, worst last — a document that made it all the way through the
// pipeline (STRUCTURED) is a more useful result than one that failed at either stage.
const STATUS_ORDER: DocumentStatus[] = [
  "STRUCTURED",
  "TEXT_EXTRACTED",
  "UPLOADED",
  "STRUCTURING_FAILED",
  "TEXT_EXTRACTION_FAILED",
];

function groupByStatus(items: SearchResultItem[]): { status: DocumentStatus; items: SearchResultItem[] }[] {
  const byStatus = new Map<DocumentStatus, SearchResultItem[]>();
  for (const item of items) {
    byStatus.set(item.status, [...(byStatus.get(item.status) ?? []), item]);
  }
  return STATUS_ORDER.filter((s) => byStatus.has(s)).map((status) => ({ status, items: byStatus.get(status)! }));
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
    setFilters([...filters, { field: "", op: "eq", value: "" }]);
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
          Structured filters
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
              <option value="eq">equals</option>
              <option value="contains">contains</option>
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
          <label>Semantic query (v1 seam — not active until an embedding provider is configured)</label>
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
          {groupByStatus(result.items).map((group) => (
            <div key={group.status} style={{ marginBottom: 16 }}>
              <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 8 }}>
                <StatusPill status={group.status} />
                <span className="muted" style={{ fontSize: 12 }}>
                  {group.items.length}
                </span>
              </div>
              <div className="item-bar-list">
                {group.items.map((item) => (
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
                        <div className="item-bar-meta">{item.docType}</div>
                      </div>
                    </DocumentPreview>
                    <div className="item-bar-right">
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
          ))}
        </div>
      )}
    </div>
  );
}
