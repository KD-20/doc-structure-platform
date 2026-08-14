import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { errorMessage, guestClient } from "../api/client";
import type { GuestDocument } from "../api/types";
import { StatusPill } from "../components/StatusPill";
import { FieldsTable } from "../components/FieldsTable";
import { BackButton } from "../components/BackButton";

export function GuestDocumentPage() {
  const { token, documentId } = useParams();
  const [doc, setDoc] = useState<GuestDocument | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token || !documentId) return;
    guestClient(token)
      .get<GuestDocument>(`/guest/documents/${documentId}`)
      .then((res) => setDoc(res.data))
      .catch((err) => setError(errorMessage(err)));
  }, [token, documentId]);

  return (
    <div className="main" style={{ maxWidth: 700, margin: "0 auto" }}>
      <BackButton fallback="/" />
      <h1>Shared document</h1>
      {error && <div className="error-banner">{error}</div>}
      {doc && (
        <div className="card">
          <p>
            <strong>{doc.filename}</strong> <StatusPill status={doc.status} /> — {doc.docType}
          </p>
          {doc.fields ? <FieldsTable fields={doc.fields} /> : <p className="muted">Not structured yet.</p>}
        </div>
      )}
      <p className="muted">
        <Link to={`/guest/${token}/search`}>Search within shared documents</Link>
      </p>
    </div>
  );
}
