import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { apiClient, errorMessage } from "../api/client";
import type { DocumentSummary, EffectiveRuleSet, ExtractedData, ExtractionRun } from "../api/types";
import { StatusPill } from "../components/StatusPill";
import { FieldsTable } from "../components/FieldsTable";
import { DocumentPreview } from "../components/DocumentPreview";
import { TypeaheadInput } from "../components/TypeaheadInput";
import { DownloadIcon, RefreshIcon, TrashIcon } from "../components/icons";
import { useAuth } from "../auth/AuthContext";
import { isAdminRole, isEditorRole } from "../auth/roles";

export function DocumentDetailPage() {
  const { tenantId, documentId } = useParams();
  const navigate = useNavigate();
  const { role } = useAuth();
  const isAdmin = isAdminRole(role);
  const canEdit = isEditorRole(role);
  const [doc, setDoc] = useState<DocumentSummary | null>(null);
  const [rawText, setRawText] = useState<string>("");
  const [showRawText, setShowRawText] = useState(false);
  const [runs, setRuns] = useState<ExtractionRun[]>([]);
  const [extracted, setExtracted] = useState<ExtractedData[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [triggering, setTriggering] = useState(false);
  const [deleting, setDeleting] = useState(false);
  // Extraction runs and structured fields are secondary detail, not needed at a glance —
  // each is its own discrete row, collapsed behind its own toggle rather than one combined block.
  const [showRuns, setShowRuns] = useState(false);
  const [showFields, setShowFields] = useState(false);

  // A document can land on a generic auto-classified label ("pdf_document", "image", ...)
  // with no matching rule set — this lets it be corrected after the fact rather than stuck.
  const [editingType, setEditingType] = useState(false);
  const [typeInput, setTypeInput] = useState("");
  const [savingType, setSavingType] = useState(false);
  const [existingDocTypes, setExistingDocTypes] = useState<string[]>([]);

  async function load() {
    try {
      const [docRes, runsRes, dataRes] = await Promise.all([
        apiClient.get<DocumentSummary>(`/tenants/${tenantId}/documents/${documentId}`),
        apiClient.get<ExtractionRun[]>(`/tenants/${tenantId}/documents/${documentId}/extraction-runs`),
        apiClient.get<ExtractedData[]>(`/tenants/${tenantId}/documents/${documentId}/extracted-data`),
      ]);
      setDoc(docRes.data);
      setRuns(runsRes.data);
      setExtracted(dataRes.data);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenantId, documentId]);

  useEffect(() => {
    apiClient
      .get<EffectiveRuleSet[]>(`/tenants/${tenantId}/rule-sets/effective`)
      .then((res) => setExistingDocTypes([...new Set(res.data.map((r) => r.docType))]))
      .catch(() => setExistingDocTypes([]));
  }, [tenantId]);

  async function loadRawText() {
    try {
      const { data } = await apiClient.get<{ rawText: string }>(`/tenants/${tenantId}/documents/${documentId}/raw-text`);
      setRawText(data.rawText ?? "(no text extracted)");
      setShowRawText(true);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function triggerExtraction() {
    setError(null);
    setTriggering(true);
    try {
      await apiClient.post(`/tenants/${tenantId}/documents/${documentId}/extraction-runs`);
      await load();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setTriggering(false);
    }
  }

  function startEditingType() {
    setTypeInput(doc?.docType ?? "");
    setEditingType(true);
  }

  async function saveType() {
    if (!typeInput.trim()) return;
    setError(null);
    setSavingType(true);
    try {
      await apiClient.patch(`/tenants/${tenantId}/documents/${documentId}/doc-type`, { docType: typeInput.trim() });
      setEditingType(false);
      await load(); // re-fetch: type change re-runs extraction if there's text to work with
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSavingType(false);
    }
  }

  async function handleDownload() {
    if (!doc) return;
    setError(null);
    try {
      // Authenticated blob fetch, not a plain <a href>: the JWT lives in-memory (not a
      // cookie), so a raw browser navigation to the download URL would carry no auth header
      // and 401. Same technique as DocumentPreview/DocumentThumbnail.
      const res = await apiClient.get(`/tenants/${tenantId}/documents/${documentId}/download`, {
        responseType: "blob",
      });
      const url = URL.createObjectURL(res.data as Blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = doc.filename;
      link.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function handleDelete() {
    if (!doc || !window.confirm(`Delete "${doc.filename}"? This can't be undone.`)) return;
    setError(null);
    setDeleting(true);
    try {
      await apiClient.delete(`/tenants/${tenantId}/documents/${documentId}`);
      navigate(`/t/${tenantId}/documents`);
    } catch (err) {
      setError(errorMessage(err));
      setDeleting(false);
    }
  }

  if (!doc) {
    return error ? <div className="error-banner">{error}</div> : <p className="muted">Loading...</p>;
  }

  return (
    <div>
      {error && <div className="error-banner">{error}</div>}

      <div className="card doc-detail-card">
        <div className="doc-detail-preview">
          <DocumentPreview
            tenantId={tenantId!}
            documentId={documentId!}
            contentType={doc.contentType}
            filename={doc.filename}
          />
        </div>

        <div className="doc-detail-info">
          <div className="doc-detail-header">
            <h1 className="doc-detail-title">{doc.filename}</h1>
            <div className="doc-detail-actions">
              {canEdit && (
                <button
                  className={`icon-btn secondary${triggering ? " spinning" : ""}`}
                  title="Re-run structured extraction"
                  onClick={triggerExtraction}
                  disabled={triggering}
                >
                  <RefreshIcon />
                </button>
              )}
              <button className="icon-btn secondary" title="Download original" onClick={handleDownload}>
                <DownloadIcon />
              </button>
              {isAdmin && (
                <button
                  className="icon-btn danger"
                  title="Delete document"
                  onClick={handleDelete}
                  disabled={deleting}
                >
                  <TrashIcon />
                </button>
              )}
            </div>
          </div>

          <p style={{ display: "flex", alignItems: "center", gap: 6, flexWrap: "wrap" }}>
            Type:{" "}
            {editingType ? (
              <>
                <TypeaheadInput
                  value={typeInput}
                  onChange={setTypeInput}
                  suggestions={existingDocTypes}
                  inputStyle={{ width: 180 }}
                  autoFocus
                />
                <button className="small" onClick={saveType} disabled={savingType || !typeInput.trim()}>
                  {savingType ? "Saving..." : "Save"}
                </button>
                <button className="secondary small" onClick={() => setEditingType(false)} disabled={savingType}>
                  Cancel
                </button>
              </>
            ) : (
              <>
                <strong>{doc.docType}</strong>
                {canEdit && (
                  <button className="secondary small" onClick={startEditingType}>
                    Change
                  </button>
                )}
              </>
            )}
            &nbsp; Status: <StatusPill status={doc.status} /> &nbsp; Size: {(doc.fileSizeBytes / 1024).toFixed(1)} KB
          </p>

          <button className="secondary" onClick={loadRawText}>
            {showRawText ? "Refresh raw text" : "View extracted raw text"}
          </button>

          {showRawText && <pre className="json-view" style={{ marginTop: 12, whiteSpace: "pre-wrap" }}>{rawText}</pre>}
        </div>
      </div>

      <div className="card">
        <div className="doc-detail-status-row" onClick={() => setShowRuns((s) => !s)}>
          <h2 style={{ fontSize: 15, margin: 0 }}>Extraction runs</h2>
          <span className="muted">
            {runs.length} {showRuns ? "▲" : "▾"}
          </span>
        </div>
        {showRuns && (
          <>
            <table style={{ marginTop: 12 }}>
              <thead>
                <tr>
                  <th>Strategy</th>
                  <th>Status</th>
                  <th>Started</th>
                  <th>Error</th>
                </tr>
              </thead>
              <tbody>
                {runs.map((r) => (
                  <tr key={r.id}>
                    <td>{r.strategy}</td>
                    <td>
                      <StatusPill status={r.status} />
                    </td>
                    <td>{r.startedAt ? new Date(r.startedAt).toLocaleString() : "-"}</td>
                    <td className="muted">{r.errorMessage ?? ""}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {runs.length === 0 && <p className="muted">No extraction runs yet.</p>}
          </>
        )}
      </div>

      <div className="card">
        <div className="doc-detail-status-row" onClick={() => setShowFields((s) => !s)}>
          <h2 style={{ fontSize: 15, margin: 0 }}>Structured fields</h2>
          <span className="muted">
            {extracted.length} {showFields ? "▲" : "▾"}
          </span>
        </div>
        {showFields && (
          <>
            {extracted.map((ed) => (
              <div key={ed.id} style={{ marginTop: 16 }}>
                <h3 style={{ fontSize: 13, marginBottom: 6 }}>
                  <StatusPill status={ed.status} />{" "}
                  <span className="muted">(overall confidence {(ed.overallConfidence * 100).toFixed(0)}%)</span>
                </h3>
                <FieldsTable fields={ed.fields} />
              </div>
            ))}
            {extracted.length === 0 && <p className="muted">No structured data yet.</p>}
          </>
        )}
      </div>
    </div>
  );
}
