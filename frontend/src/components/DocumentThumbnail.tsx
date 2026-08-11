import { useEffect, useState } from "react";
import { apiClient } from "../api/client";
import type { DocumentSummary } from "../api/types";
import { DocumentIcon } from "./icons";

/** Renders an actual image preview for image/* documents (fetched as an authenticated blob — a plain <img src> can't carry the Authorization header); everything else falls back to a generic file icon rather than pulling in a PDF-rendering dependency. */
export function DocumentThumbnail({ tenantId, doc }: { tenantId: string; doc: DocumentSummary }) {
  const isImage = doc.contentType?.startsWith("image/") ?? false;
  const [src, setSrc] = useState<string | null>(null);

  useEffect(() => {
    if (!isImage) return;
    let cancelled = false;
    let objectUrl: string | null = null;

    apiClient
      .get(`/tenants/${tenantId}/documents/${doc.id}/download`, { responseType: "blob" })
      .then((res) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(res.data as Blob);
        setSrc(objectUrl);
      })
      .catch(() => {
        // fall back to the icon silently — a thumbnail is a nicety, not critical
      });

    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [tenantId, doc.id, isImage]);

  if (isImage && src) {
    return <img src={src} alt={doc.filename} className="doc-thumb" />;
  }
  return (
    <div className="doc-thumb doc-thumb-icon">
      <DocumentIcon />
    </div>
  );
}
