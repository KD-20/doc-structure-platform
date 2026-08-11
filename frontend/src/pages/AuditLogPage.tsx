import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { apiClient, errorMessage } from "../api/client";
import type { AuditLogEntry, DocumentSummary, Member, Page } from "../api/types";
import { DocumentThumbnail } from "../components/DocumentThumbnail";
import { DocumentIcon, InfoIcon } from "../components/icons";

const PAGE_SIZE = 20;

// The fixed, known set of entityType values every @Audited/AuditService.record call in the
// backend actually uses — not fetched dynamically, since these are stable category names
// defined in code, not user data.
const CATEGORIES = [
  "DOCUMENT",
  "EXTRACTION_RUN",
  "SEARCH",
  "RULE_SET",
  "TENANT_MEMBERSHIP",
  "GUEST_SHARE_LINK",
];

/** Custom CSS-hover tooltip, not the native `title` attribute — title has an inconsistent
    ~1s delay across browsers and reads as "just not working" for a quick glance. */
function HoverTip({ text }: { text: string }) {
  return (
    <span className="icon-hint">
      <InfoIcon />
      <span className="icon-hint-tooltip">{text}</span>
    </span>
  );
}

function humanize(value: string): string {
  return value
    .toLowerCase()
    .split("_")
    .map((w) => w[0].toUpperCase() + w.slice(1))
    .join(" ");
}

/** DOCUMENT_UPLOADED's entityId IS the document id; EXTRACTION_RUN_* entries carry it in metadata.documentId instead (their entityId is the run's own id) — see ExtractionService/DocumentService on the backend. Doc lookup only applies to these two categories; every other category (SEARCH, RULE_SET, TENANT_MEMBERSHIP, GUEST_SHARE_LINK) isn't tied to one document at all. */
function documentIdFor(entry: AuditLogEntry): string | null {
  if (entry.entityType === "DOCUMENT" && entry.entityId) return entry.entityId;
  if (entry.entityType === "EXTRACTION_RUN" && typeof entry.metadata?.documentId === "string") {
    return entry.metadata.documentId as string;
  }
  return null;
}

/** SEARCH_PERFORMED's metadata (see SearchService.search) — q wins over semanticQuery when both are set, matching how the backend itself treats them. */
function searchQueryFor(entry: AuditLogEntry): string | null {
  if (entry.entityType !== "SEARCH") return null;
  const q = typeof entry.metadata?.q === "string" ? entry.metadata.q : "";
  const semanticQuery = typeof entry.metadata?.semanticQuery === "string" ? entry.metadata.semanticQuery : "";
  return q || semanticQuery || "(empty query)";
}

interface EntryGroup {
  key: string;
  docId: string | null;
  entries: AuditLogEntry[];
}

// Entries sharing a document id collapse into a single row (newest-first order is preserved
// since the backend already returns entries that way); everything else — SEARCH, RULE_SET,
// TENANT_MEMBERSHIP, GUEST_SHARE_LINK entries have no document to key off — stays one row per
// entry, same as before.
function groupByDocument(entries: AuditLogEntry[]): EntryGroup[] {
  const groups: EntryGroup[] = [];
  const indexByDocId = new Map<string, number>();
  for (const entry of entries) {
    const docId = documentIdFor(entry);
    if (docId) {
      const idx = indexByDocId.get(docId);
      if (idx !== undefined) {
        groups[idx].entries.push(entry);
        continue;
      }
      indexByDocId.set(docId, groups.length);
      groups.push({ key: docId, docId, entries: [entry] });
    } else {
      groups.push({ key: entry.id, docId: null, entries: [entry] });
    }
  }
  return groups;
}

export function AuditLogPage() {
  const { tenantId } = useParams();
  const [page, setPage] = useState<Page<AuditLogEntry> | null>(null);
  const [documents, setDocuments] = useState<DocumentSummary[]>([]);
  const [members, setMembers] = useState<Member[]>([]);
  const [pageNumber, setPageNumber] = useState(0);
  const [category, setCategory] = useState("DOCUMENT");
  const [openGroupKey, setOpenGroupKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    apiClient
      .get<Member[]>(`/tenants/${tenantId}/members`)
      .then((res) => setMembers(res.data))
      .catch(() => setMembers([]));
  }, [tenantId]);

  useEffect(() => {
    setLoading(true);
    setOpenGroupKey(null);
    Promise.all([
      apiClient.get<Page<AuditLogEntry>>(`/tenants/${tenantId}/audit-log`, {
        params: { entityType: category, page: pageNumber, size: PAGE_SIZE },
      }),
      apiClient.get<Page<DocumentSummary>>(`/tenants/${tenantId}/documents`, { params: { size: 200 } }),
    ])
      .then(([auditRes, docsRes]) => {
        setPage(auditRes.data);
        setDocuments(docsRes.data.content);
      })
      .catch((err) => setError(errorMessage(err)))
      .finally(() => setLoading(false));
  }, [tenantId, category, pageNumber]);

  // Same click-outside-closes behavior as the Documents page's history dropdown.
  useEffect(() => {
    function closeOnOutsideClick(e: globalThis.MouseEvent) {
      const target = e.target as Element;
      if (!target.closest(".history-dropdown")) {
        setOpenGroupKey(null);
      }
    }
    document.addEventListener("mousedown", closeOnOutsideClick);
    return () => document.removeEventListener("mousedown", closeOnOutsideClick);
  }, []);

  const docsById = new Map(documents.map((d) => [d.id, d]));
  const membersById = new Map(members.map((m) => [m.userId, m]));

  function actorNameFor(e: AuditLogEntry): string {
    if (e.actorType === "GUEST") return "Guest";
    if (e.actorType === "SYSTEM") return "System";
    const member = e.actorUserId ? membersById.get(e.actorUserId) : undefined;
    return member?.fullName || member?.email || `User ${e.actorUserId?.slice(0, 8)}`;
  }

  const groups = page ? groupByDocument(page.content) : [];

  return (
    <div>
      <h1>Audit Log</h1>
      {error && <div className="error-banner">{error}</div>}

      <div className="form-row" style={{ maxWidth: 240, marginBottom: 16 }}>
        <label>Category</label>
        <select
          value={category}
          onChange={(e) => {
            setCategory(e.target.value);
            setPageNumber(0);
          }}
        >
          {CATEGORIES.map((c) => (
            <option key={c} value={c}>
              {humanize(c)}
            </option>
          ))}
        </select>
      </div>

      <div className="card">
        <div style={{ overflowX: "auto" }}>
          <table>
            <thead>
              <tr>
                <th></th>
                <th>Name</th>
                <th>Latest action</th>
                <th>User</th>
                <th>Time</th>
                <th>History</th>
              </tr>
            </thead>
            <tbody>
              {groups.map((group) => {
                const doc = group.docId ? docsById.get(group.docId) : undefined;
                const latest = group.entries[0];
                const hasHistory = group.entries.length > 1;
                const isOpen = openGroupKey === group.key;
                const title = group.docId
                  ? doc?.filename ?? "Deleted document"
                  : searchQueryFor(latest) ?? humanize(latest.entityType);

                return (
                  <tr key={group.key}>
                    <td title={title}>
                      {group.docId ? (
                        doc ? (
                          <DocumentThumbnail tenantId={tenantId!} doc={doc} />
                        ) : (
                          <div className="doc-thumb doc-thumb-icon">
                            <DocumentIcon />
                          </div>
                        )
                      ) : (
                        <div className="item-bar-icon neutral" style={{ width: 40, height: 40, minWidth: 40 }}>
                          <InfoIcon />
                        </div>
                      )}
                    </td>
                    <td>
                      <div style={{ display: "flex", alignItems: "center", gap: 4 }}>
                        <span>{title}</span>
                        {group.docId && <HoverTip text={group.docId} />}
                      </div>
                    </td>
                    <td>{humanize(latest.action)}</td>
                    <td>
                      <div style={{ display: "flex", alignItems: "center", gap: 4 }}>
                        <span>{actorNameFor(latest)}</span>
                        {latest.actorType === "USER" &&
                          membersById.get(latest.actorUserId ?? "")?.email && (
                            <HoverTip text={membersById.get(latest.actorUserId ?? "")!.email} />
                          )}
                      </div>
                    </td>
                    <td className="muted" style={{ whiteSpace: "nowrap" }}>
                      {new Date(latest.createdAt).toLocaleString()}
                    </td>
                    <td>
                      {hasHistory ? (
                        <div className="history-dropdown">
                          <button
                            className="history-dropdown-toggle"
                            title={`View all ${group.entries.length} events for this document`}
                            onClick={() => setOpenGroupKey(isOpen ? null : group.key)}
                          >
                            {group.entries.length} events {isOpen ? "▲" : "▾"}
                          </button>
                          {isOpen && (
                            <div className="history-dropdown-panel">
                              <div className="history-dropdown-panel-header">
                                <span className="muted" style={{ fontSize: 12 }}>
                                  History
                                </span>
                                <button
                                  className="icon-btn secondary"
                                  title="Close"
                                  onClick={() => setOpenGroupKey(null)}
                                >
                                  ×
                                </button>
                              </div>
                              {group.entries.map((e) => (
                                <div className="history-dropdown-row" key={e.id}>
                                  <span>{humanize(e.action)}</span>
                                  <span className="muted">{actorNameFor(e)}</span>
                                  <span className="muted">{new Date(e.createdAt).toLocaleDateString()}</span>
                                </div>
                              ))}
                            </div>
                          )}
                        </div>
                      ) : (
                        <span className="muted">—</span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        {!loading && groups.length === 0 && <p className="muted">No activity yet.</p>}

        {page && page.totalPages > 1 && (
          <div className="pagination-controls">
            <button className="secondary" disabled={pageNumber === 0} onClick={() => setPageNumber((p) => p - 1)}>
              ← Previous
            </button>
            <span className="muted">
              Page {page.number + 1} of {page.totalPages}
            </span>
            <button
              className="secondary"
              disabled={page.number + 1 >= page.totalPages}
              onClick={() => setPageNumber((p) => p + 1)}
            >
              Next →
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
