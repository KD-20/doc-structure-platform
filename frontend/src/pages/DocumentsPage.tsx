import { useEffect, useRef, useState, type DragEvent, type FormEvent } from "react";
import { Link, useParams } from "react-router-dom";
import { apiClient, errorMessage, getAuthToken } from "../api/client";
import type { DocumentStatus, DocumentSummary, EffectiveRuleSet, ExtractionRun, Page } from "../api/types";
import { StatusPill } from "../components/StatusPill";
import { DocumentThumbnail } from "../components/DocumentThumbnail";
import { PipelineSteps } from "../components/PipelineSteps";
import { TypeaheadInput } from "../components/TypeaheadInput";
import { RefreshIcon, UploadIcon } from "../components/icons";
import { formatCategoryForDocType, formatCategoryForFilename, formatLabel } from "../utils/docTypeFormat";
import { displayStatus } from "../utils/documentDisplayStatus";
import { useAuth } from "../auth/AuthContext";
import { isAdminRole, isEditorRole } from "../auth/roles";

// Fetch a real page at a time rather than everything in one call — the same fix applied to
// the guest-link document picker, needed here too now that the list itself can grow large.
const PAGE_SIZE = 10;

// "···" opens a floating, paginated picker instead of growing the chip row inline — paginated
// client-side here since /search/doc-types returns the full (usually short) list in one call.
const DOC_TYPE_MENU_PAGE_SIZE = 8;

// A handful of shortcut chips stay inline before the row hands off to "···"; the last one
// (5th) hides on narrow screens via CSS (.chip-responsive-hide), leaving 4 there.
const INLINE_DOC_TYPE_CHIPS = 5;

export function DocumentsPage() {
  const { tenantId } = useParams();
  const { role } = useAuth();
  const isAdmin = isAdminRole(role);
  // Mirrors the backend: upload/doc-type-change/extraction-trigger all need EDITOR
  // (DocumentController's POST/PATCH). A VIEWER shouldn't see an Upload button or a rerun
  // control that would just 403 on click.
  const canEdit = isEditorRole(role);
  const [page, setPage] = useState<Page<DocumentSummary> | null>(null);
  const [pageNumber, setPageNumber] = useState(0);
  const [docTypeFilter, setDocTypeFilter] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [docTypeOverride, setDocTypeOverride] = useState("");
  const [existingDocTypes, setExistingDocTypes] = useState<string[]>([]);
  // Doc types actually present on a document in this tenant right now — distinct from
  // existingDocTypes (every type extraction *could* use, custom or default) — this is what
  // the filter dropdown offers, since filtering by a type nothing has yet would always be empty.
  const [usedDocTypes, setUsedDocTypes] = useState<string[]>([]);
  const [docTypeMenuOpen, setDocTypeMenuOpen] = useState(false);
  const [docTypeMenuPage, setDocTypeMenuPage] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [dragging, setDragging] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [retriggeringDocId, setRetriggeringDocId] = useState<string | null>(null);

  // Only one status dropdown open at a time; its run history is fetched the first time it
  // opens (and refetched live whenever an SSE event arrives for that same document while
  // it's open — see the document-status listener below).
  const [openStatusDocId, setOpenStatusDocId] = useState<string | null>(null);
  const [historyByDoc, setHistoryByDoc] = useState<Record<string, ExtractionRun[]>>({});
  const [loadingHistoryFor, setLoadingHistoryFor] = useState<string | null>(null);

  useEffect(() => {
    function closeOnOutsideClick(e: globalThis.MouseEvent) {
      const target = e.target as Element;
      if (!target.closest(".doctype-menu")) {
        setDocTypeMenuOpen(false);
      }
      if (!target.closest(".history-dropdown")) {
        setOpenStatusDocId(null);
      }
    }
    document.addEventListener("mousedown", closeOnOutsideClick);
    return () => document.removeEventListener("mousedown", closeOnOutsideClick);
  }, []);

  async function loadHistory(docId: string) {
    setLoadingHistoryFor(docId);
    try {
      const { data } = await apiClient.get<ExtractionRun[]>(`/tenants/${tenantId}/documents/${docId}/extraction-runs`);
      setHistoryByDoc((prev) => ({ ...prev, [docId]: data }));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoadingHistoryFor((cur) => (cur === docId ? null : cur));
    }
  }

  function toggleStatusDropdown(docId: string) {
    if (openStatusDocId === docId) {
      setOpenStatusDocId(null);
      return;
    }
    setOpenStatusDocId(docId);
    loadHistory(docId);
  }

  // Read inside the SSE handler below, which is set up once per tenantId and would otherwise
  // close over whatever openStatusDocId was at connection time, not its current value.
  const openStatusDocIdRef = useRef<string | null>(null);
  useEffect(() => {
    openStatusDocIdRef.current = openStatusDocId;
  }, [openStatusDocId]);

  // Suggestions only (a <datalist>, not a hard-restricted <select>) for the optional
  // upload-time override — "effective" doc types are every one extraction can actually use
  // right now, including platform-shipped defaults the tenant hasn't customized, but typing
  // something new is still allowed (e.g. to name a not-yet-defined type).
  useEffect(() => {
    apiClient
      .get<EffectiveRuleSet[]>(`/tenants/${tenantId}/rule-sets/effective`)
      .then((res) => setExistingDocTypes([...new Set(res.data.map((r) => r.docType))]))
      .catch(() => setExistingDocTypes([]));
  }, [tenantId]);

  async function loadUsedDocTypes() {
    try {
      const { data } = await apiClient.get<string[]>(`/tenants/${tenantId}/search/doc-types`);
      setUsedDocTypes(data);
    } catch {
      setUsedDocTypes([]);
    }
  }

  useEffect(() => {
    loadUsedDocTypes();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenantId]);

  async function load() {
    try {
      const { data } = await apiClient.get<Page<DocumentSummary>>(`/tenants/${tenantId}/documents`, {
        params: { page: pageNumber, size: PAGE_SIZE, ...(docTypeFilter ? { docType: docTypeFilter } : {}) },
      });
      setPage(data);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenantId, docTypeFilter, pageNumber]);

  // Live status updates instead of needing a manual refresh — structured extraction now runs
  // asynchronously in the background (see ExtractionService#enqueueExtraction), so a document
  // can genuinely sit at PENDING/RUNNING for a little while; this is what makes that visible in
  // real time, both to *other* tabs/users viewing this tenant's documents and to whichever
  // status dropdown (see toggleStatusDropdown) happens to be open when a run finishes.
  // EventSource can't send an Authorization header, so the token travels as a query param
  // instead — see JwtAuthFilter's query-param fallback.
  useEffect(() => {
    if (!tenantId) return;
    const token = getAuthToken();
    const source = new EventSource(
      `/api/tenants/${tenantId}/documents/events${token ? `?token=${encodeURIComponent(token)}` : ""}`,
    );
    source.addEventListener("document-status", (event) => {
      const payload = JSON.parse((event as MessageEvent).data) as {
        documentId: string;
        status: DocumentStatus;
        docType: string;
      };
      setPage((prev) => {
        if (!prev || !prev.content.some((d) => d.id === payload.documentId)) return prev;
        return {
          ...prev,
          content: prev.content.map((d) =>
            d.id === payload.documentId ? { ...d, status: payload.status, docType: payload.docType } : d,
          ),
        };
      });
      if (openStatusDocIdRef.current === payload.documentId) {
        loadHistory(payload.documentId);
      }
    });
    // A run starting doesn't touch the document's own `status` at all (PENDING/RUNNING have no
    // DocumentStatus counterpart) — without this, there'd be no live signal a job is in flight
    // until it finishes, for anyone who didn't personally trigger it. See
    // DocumentEventService#publishExtractionStatus on the backend.
    source.addEventListener("extraction-status", (event) => {
      const payload = JSON.parse((event as MessageEvent).data) as {
        documentId: string;
        runStatus: DocumentSummary["latestExtractionRunStatus"];
      };
      setPage((prev) => {
        if (!prev || !prev.content.some((d) => d.id === payload.documentId)) return prev;
        return {
          ...prev,
          content: prev.content.map((d) =>
            d.id === payload.documentId ? { ...d, latestExtractionRunStatus: payload.runStatus } : d,
          ),
        };
      });
      if (openStatusDocIdRef.current === payload.documentId) {
        loadHistory(payload.documentId);
      }
    });
    // Connection drops (e.g. server restart) are expected occasionally — EventSource
    // reconnects on its own; nothing to handle here beyond not crashing the page.
    source.onerror = () => {};
    return () => source.close();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenantId]);


  function handleFilterChange(value: string) {
    setDocTypeFilter(value);
    setPageNumber(0); // a filter change can invalidate the current page index
    setDocTypeMenuOpen(false);
  }

  async function retriggerExtraction(docId: string) {
    setRetriggeringDocId(docId);
    setError(null);
    try {
      // Async now (see ExtractionService#enqueueExtraction) — this returns as soon as a
      // PENDING run exists, not once it's finished. Refreshing the list here just shows that
      // PENDING/RUNNING state immediately; the eventual SUCCEEDED/FAILED transition arrives via
      // the SSE stream above. Opening the status dropdown lets the user actually watch it happen.
      await apiClient.post(`/tenants/${tenantId}/documents/${docId}/extraction-runs`);
      await load();
      setOpenStatusDocId(docId);
      await loadHistory(docId);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setRetriggeringDocId(null);
    }
  }

  async function handleDelete(doc: DocumentSummary) {
    if (!window.confirm(`Delete "${doc.filename}"? This can't be undone.`)) return;
    setError(null);
    try {
      await apiClient.delete(`/tenants/${tenantId}/documents/${doc.id}`);
      await load();
      await loadUsedDocTypes();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  function handleDrop(e: DragEvent<HTMLDivElement>) {
    e.preventDefault();
    setDragging(false);
    const dropped = e.dataTransfer.files?.[0];
    if (dropped) setFile(dropped);
  }

  async function handleUpload(e: FormEvent) {
    e.preventDefault();
    if (!file) return;
    setError(null);

    // Catches "selected pdf, uploaded a jpeg": only fires when the typed type is one of the
    // system's own format categories (or an alias of one, e.g. "pdf") — free-form business
    // types like "invoice"/"resume" are format-agnostic and intentionally not checked here.
    const expectedCategory = formatCategoryForDocType(docTypeOverride);
    const actualCategory = formatCategoryForFilename(file.name);
    if (expectedCategory && actualCategory && expectedCategory !== actualCategory) {
      setError(
        `"${docTypeOverride.trim()}" expects a ${formatLabel(expectedCategory)} file, but "${file.name}" looks like a ${formatLabel(actualCategory)}. Pick the matching type, or leave it blank to upload as unstructured.`,
      );
      return;
    }

    setUploading(true);
    try {
      // docType is optional: leave it blank and the backend classifies it automatically
      // from the document's content against existing rule sets (DocTypeClassifier), running
      // structured extraction right away on a confident match. Set it explicitly to skip
      // classification entirely — useful when you already know the type, or to avoid a
      // generic fallback label like "pdf_document"/"image". See DocumentService#upload.
      const form = new FormData();
      form.append("file", file);
      if (docTypeOverride.trim()) {
        form.append("docType", docTypeOverride.trim());
      }
      await apiClient.post(`/tenants/${tenantId}/documents`, form, {
        headers: { "Content-Type": "multipart/form-data" },
      });
      setFile(null);
      setDocTypeOverride("");
      if (fileInputRef.current) fileInputRef.current.value = "";
      setPageNumber(0);
      await load();
      await loadUsedDocTypes();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setUploading(false);
    }
  }

  // Live check, not just an on-submit one — same rule as handleUpload, computed here so the
  // mismatch is visible (and the Upload button disabled) before the user even clicks it.
  let typeMismatch: string | null = null;
  if (file) {
    const expectedCategory = formatCategoryForDocType(docTypeOverride);
    const actualCategory = formatCategoryForFilename(file.name);
    if (expectedCategory && actualCategory && expectedCategory !== actualCategory) {
      typeMismatch = `"${docTypeOverride.trim()}" expects a ${formatLabel(expectedCategory)} file, but "${file.name}" looks like a ${formatLabel(actualCategory)}.`;
    }
  }

  const docTypeMenuTotalPages = Math.max(1, Math.ceil(usedDocTypes.length / DOC_TYPE_MENU_PAGE_SIZE));
  const docTypeMenuItems = usedDocTypes.slice(
    docTypeMenuPage * DOC_TYPE_MENU_PAGE_SIZE,
    (docTypeMenuPage + 1) * DOC_TYPE_MENU_PAGE_SIZE,
  );
  const inlineDocTypeChips = usedDocTypes.slice(0, INLINE_DOC_TYPE_CHIPS);

  return (
    <div>
      <h1>Documents</h1>
      {error && <div className="error-banner">{error}</div>}

      {canEdit && (
      <div className="card">
        <form onSubmit={handleUpload}>
          <div
            className={`dropzone${dragging ? " dragging" : ""}`}
            onClick={() => fileInputRef.current?.click()}
            onDragOver={(e) => {
              e.preventDefault();
              setDragging(true);
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={handleDrop}
          >
            <div className="dropzone-icon">
              <UploadIcon />
            </div>
            <div className="dropzone-title">{file ? "Ready to upload" : "Drag & drop a file, or click to browse"}</div>
            <p className="muted" style={{ margin: 0 }}>
              PDF, Word, images, HTML, or plain text
            </p>
            {file && <span className="dropzone-file">{file.name}</span>}
            <input
              ref={fileInputRef}
              id="file-input"
              type="file"
              style={{ display: "none" }}
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
          </div>

          <div style={{ display: "flex", gap: 12, alignItems: "flex-end", marginTop: 16 }}>
            <div className="form-row" style={{ flex: 1, marginBottom: 0, maxWidth: 260 }}>
              <label>
                Document type (optional — leave blank to upload it unstructured; we won't guess a
                type, but it still gets indexed for search)
              </label>
              <TypeaheadInput
                value={docTypeOverride}
                onChange={setDocTypeOverride}
                placeholder="unstructured"
                // Both sources: types extraction can already use (rule sets, custom or
                // default) and types already sitting on real documents in this tenant — the
                // latter can include ones with no rule set at all (e.g. a generic
                // extension-derived fallback), still worth reusing as a name.
                suggestions={[...new Set([...existingDocTypes, ...usedDocTypes])].sort()}
              />
              {typeMismatch && (
                <p className="muted" style={{ color: "var(--danger)", fontSize: 12, margin: "4px 0 0" }}>
                  {typeMismatch}
                </p>
              )}
            </div>
            <button type="submit" className="pill" disabled={uploading || !file || !!typeMismatch}>
              {uploading ? "Uploading..." : "Upload"}
            </button>
          </div>
        </form>
      </div>
      )}

      <div className="card">
        <div className="form-row">
          <label>Filter by document type</label>
          <div className="chip-list">
            <button
              type="button"
              className={`chip${docTypeFilter === "" ? " selected" : ""}`}
              onClick={() => handleFilterChange("")}
            >
              All
            </button>
            {/* The active filter always gets its own chip so it's visible even when it isn't
                one of the inline shortcuts below (or was since deleted from usedDocTypes). */}
            {docTypeFilter && !inlineDocTypeChips.includes(docTypeFilter) && (
              <button type="button" className="chip selected" onClick={() => setDocTypeMenuOpen((o) => !o)}>
                {docTypeFilter}
              </button>
            )}
            {inlineDocTypeChips.map((t, i) => (
              <button
                key={t}
                type="button"
                className={`chip${docTypeFilter === t ? " selected" : ""}${i === INLINE_DOC_TYPE_CHIPS - 1 ? " chip-responsive-hide" : ""}`}
                onClick={() => handleFilterChange(t)}
              >
                {t}
              </button>
            ))}
            <div className="doctype-menu">
              <button
                type="button"
                className="chip chip-more"
                onClick={() => {
                  setDocTypeMenuPage(0);
                  setDocTypeMenuOpen((o) => !o);
                }}
                title="Browse document types"
              >
                ···
              </button>
              {docTypeMenuOpen && (
                <div className="doctype-menu-panel">
                  <div className="doctype-menu-list">
                    {docTypeMenuItems.map((t) => (
                      <div
                        key={t}
                        className={`doctype-menu-item${docTypeFilter === t ? " active" : ""}`}
                        onClick={() => handleFilterChange(t)}
                      >
                        {t}
                      </div>
                    ))}
                    {docTypeMenuItems.length === 0 && <p className="muted" style={{ padding: 8, margin: 0 }}>No document types yet.</p>}
                  </div>
                  {docTypeMenuTotalPages > 1 && (
                    <div className="pagination-controls" style={{ marginTop: 8 }}>
                      <button
                        className="secondary small"
                        disabled={docTypeMenuPage === 0}
                        onClick={() => setDocTypeMenuPage((p) => p - 1)}
                      >
                        ← Prev
                      </button>
                      <span className="muted" style={{ fontSize: 12 }}>
                        {docTypeMenuPage + 1} / {docTypeMenuTotalPages}
                      </span>
                      <button
                        className="secondary small"
                        disabled={docTypeMenuPage + 1 >= docTypeMenuTotalPages}
                        onClick={() => setDocTypeMenuPage((p) => p + 1)}
                      >
                        Next →
                      </button>
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>

        <div style={{ overflowX: "auto" }}>
          <table>
            <thead>
              <tr>
                <th></th>
                <th>Name</th>
                <th>Size</th>
                <th>Uploaded</th>
                <th>Type</th>
                <th>Status</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {page?.content.map((d) => {
                const isStatusOpen = openStatusDocId === d.id;
                const history = historyByDoc[d.id];
                return (
                  <tr key={d.id}>
                    <td title={d.filename}>
                      <DocumentThumbnail tenantId={tenantId!} doc={d} />
                    </td>
                    <td>
                      <Link to={`/t/${tenantId}/documents/${d.id}`} title={`Open "${d.filename}"`}>
                        {d.filename}
                      </Link>
                    </td>
                    <td className="muted" title={`${d.fileSizeBytes.toLocaleString()} bytes`}>
                      {(d.fileSizeBytes / 1024).toFixed(1)} KB
                    </td>
                    <td className="muted" title={d.createdAt}>
                      {new Date(d.createdAt).toLocaleString()}
                    </td>
                    <td title={`Document type: ${d.docType}`}>
                      {d.docType === "unclassified" ? (
                        <span className="status-pill status-neutral">unclassified</span>
                      ) : (
                        <span className="tile-stat">{d.docType}</span>
                      )}
                    </td>
                    <td>
                      <div className="history-dropdown">
                        <button
                          type="button"
                          className="status-dropdown-toggle"
                          title="View live processing status"
                          onClick={() => toggleStatusDropdown(d.id)}
                        >
                          <StatusPill status={displayStatus(d)} />
                          <span className="expand-chevron">{isStatusOpen ? "▲" : "▾"}</span>
                        </button>
                        {isStatusOpen && (
                          <div className="history-dropdown-panel">
                            <div className="history-dropdown-panel-header">
                              <span className="muted" style={{ fontSize: 12 }}>
                                Live status
                              </span>
                              <button
                                className="icon-btn secondary"
                                title="Close"
                                onClick={() => setOpenStatusDocId(null)}
                              >
                                ×
                              </button>
                            </div>
                            <PipelineSteps status={d.status} latestRun={history?.[0]} />
                            {(loadingHistoryFor === d.id || (history && history.length > 0)) && (
                              <div className="history-dropdown-divider" />
                            )}
                            {loadingHistoryFor === d.id && <span className="muted">Loading history…</span>}
                            {history?.map((r) => (
                              <div className="history-dropdown-row" key={r.id} title={r.errorMessage ?? undefined}>
                                <span>{r.strategy}</span>
                                <StatusPill status={r.status} />
                                <span className="muted">
                                  {r.startedAt ? new Date(r.startedAt).toLocaleDateString() : "-"}
                                </span>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    </td>
                    <td>
                      <div style={{ display: "flex", gap: 6, alignItems: "center", justifyContent: "flex-end" }}>
                        {canEdit && (
                          <button
                            className={`icon-btn secondary${retriggeringDocId === d.id ? " spinning" : ""}`}
                            title="Re-run structured extraction"
                            onClick={() => retriggerExtraction(d.id)}
                            disabled={retriggeringDocId === d.id}
                          >
                            <RefreshIcon />
                          </button>
                        )}
                        {isAdmin && (
                          <button
                            className="danger icon-btn"
                            title={`Delete "${d.filename}"`}
                            onClick={() => handleDelete(d)}
                          >
                            ×
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        {page && page.content.length === 0 && <p className="muted">No documents yet.</p>}

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
