import { useEffect, useRef, useState, type DragEvent, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { publicClient, errorMessage } from "../api/client";
import type { PublicDocument, PublicExtractedData, PublicExtractionRun } from "../api/types";
import { StatusPill } from "../components/StatusPill";
import { PipelineSteps } from "../components/PipelineSteps";
import { FieldsTable } from "../components/FieldsTable";
import { UploadIcon } from "../components/icons";
import { BackButton } from "../components/BackButton";

/**
 * The no-login "try it before you subscribe" flow — see backend PublicDemoController. A
 * deliberately smaller feature set than the real app: upload, see status, see structured
 * fields. No rule-set editing, search, sharing, or docType override — those are what
 * registering actually gets you.
 */
export function TryItPage() {
  const [docs, setDocs] = useState<PublicDocument[]>([]);
  const [file, setFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [openDocId, setOpenDocId] = useState<string | null>(null);
  const [runsByDoc, setRunsByDoc] = useState<Record<string, PublicExtractionRun[]>>({});
  const [fieldsByDoc, setFieldsByDoc] = useState<Record<string, PublicExtractedData[]>>({});
  const [loadingDetailFor, setLoadingDetailFor] = useState<string | null>(null);

  async function loadDocs() {
    try {
      const { data } = await publicClient.get<PublicDocument[]>("/documents");
      setDocs(data);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  useEffect(() => {
    loadDocs();
  }, []);

  const quota = docs[0] ?? null;
  const usedCount = quota?.uploadsUsed ?? 0;
  const limit = quota?.uploadsLimit ?? 5;
  const atLimit = usedCount >= limit && docs.length > 0;

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
    setUploading(true);
    try {
      const form = new FormData();
      form.append("file", file);
      await publicClient.post("/documents", form, { headers: { "Content-Type": "multipart/form-data" } });
      setFile(null);
      if (fileInputRef.current) fileInputRef.current.value = "";
      await loadDocs();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setUploading(false);
    }
  }

  async function toggleDoc(docId: string) {
    if (openDocId === docId) {
      setOpenDocId(null);
      return;
    }
    setOpenDocId(docId);
    if (!runsByDoc[docId]) {
      setLoadingDetailFor(docId);
      try {
        const [runsRes, fieldsRes] = await Promise.all([
          publicClient.get<PublicExtractionRun[]>(`/documents/${docId}/extraction-runs`),
          publicClient.get<PublicExtractedData[]>(`/documents/${docId}/extracted-data`),
        ]);
        setRunsByDoc((prev) => ({ ...prev, [docId]: runsRes.data }));
        setFieldsByDoc((prev) => ({ ...prev, [docId]: fieldsRes.data }));
      } catch (err) {
        setError(errorMessage(err));
      } finally {
        setLoadingDetailFor(null);
      }
    }
  }

  return (
    <div className="main" style={{ maxWidth: 700, margin: "0 auto" }}>
      <BackButton fallback="/" />
      <h1>Try it without an account</h1>
      <p className="muted">
        Upload a document and see it structured automatically — no account needed. Limited to{" "}
        {limit} documents; <Link to="/register">register</Link> for unlimited uploads and the full feature set
        (rule sets, search, sharing, and more).
      </p>
      {error && <div className="error-banner">{error}</div>}

      <div className="card">
        {atLimit ? (
          <div>
            <p>
              <strong>You've used all {limit} trial uploads on this device.</strong>
            </p>
            <p className="muted">
              <Link to="/register">Register</Link> for unlimited uploads, or{" "}
              <Link to="/login">sign in</Link> if you already have an account.
            </p>
          </div>
        ) : (
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
                PDF, Word, images, HTML, or plain text · {usedCount} of {limit} used
              </p>
              {file && <span className="dropzone-file">{file.name}</span>}
              <input
                ref={fileInputRef}
                type="file"
                style={{ display: "none" }}
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
            </div>
            <button type="submit" className="pill" disabled={uploading || !file} style={{ marginTop: 16 }}>
              {uploading ? "Uploading..." : "Upload"}
            </button>
          </form>
        )}
      </div>

      <div className="card">
        <div className="item-bar-list">
          {docs.map((d) => {
            const isOpen = openDocId === d.id;
            const runs = runsByDoc[d.id];
            const fields = fieldsByDoc[d.id];
            return (
              <div key={d.id}>
                <div className="item-bar static" onClick={() => toggleDoc(d.id)} style={{ cursor: "pointer" }}>
                  <div className="item-bar-main">
                    <span className="item-bar-title">{d.filename}</span>
                    <div className="item-bar-meta">
                      {(d.fileSizeBytes / 1024).toFixed(1)} KB · {d.docType}
                    </div>
                  </div>
                  <div className="item-bar-right">
                    <StatusPill status={d.status} />
                    <span className="muted">{isOpen ? "▲" : "▾"}</span>
                  </div>
                </div>
                {isOpen && (
                  <div className="card" style={{ marginTop: -8, marginBottom: 12 }}>
                    <PipelineSteps status={d.status} latestRun={runs?.[0]} />
                    {loadingDetailFor === d.id && <p className="muted">Loading…</p>}
                    {fields?.map((fd) => (
                      <div key={fd.id} style={{ marginTop: 16 }}>
                        <h3 style={{ fontSize: 13, marginBottom: 6 }}>
                          <StatusPill status={fd.status} />{" "}
                          <span className="muted">(confidence {(fd.overallConfidence * 100).toFixed(0)}%)</span>
                        </h3>
                        <FieldsTable fields={fd.fields} />
                      </div>
                    ))}
                  </div>
                )}
              </div>
            );
          })}
        </div>
        {docs.length === 0 && <p className="muted">No uploads yet — try one above.</p>}
      </div>
    </div>
  );
}
