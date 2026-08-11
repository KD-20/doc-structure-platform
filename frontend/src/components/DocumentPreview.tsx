import { useEffect, useState, type ReactNode } from "react";
import { apiClient } from "../api/client";
import { DocumentIcon } from "./icons";

/**
 * Renders the actual original file inline where the browser can do it natively —
 * images via <img>, PDFs and text/HTML via <iframe> (Chrome/Firefox/Edge/Safari all have a
 * built-in PDF viewer for iframes) — always fetched as an authenticated blob, since a plain
 * src/href can't carry the Authorization header (JWT lives in-memory, not a cookie).
 * Word/Excel/PowerPoint have no native browser renderer and aren't worth a client-side
 * parsing library for v1, so they fall back to a generic file icon instead of a broken preview.
 *
 * Shown as a small thumbnail by default; click it to open a full-size modal. The thumbnail's
 * iframe (PDF/text) has pointer-events disabled so a click always enlarges instead of landing
 * on the embedded PDF viewer's own controls — clicks inside an iframe's document never bubble
 * to this component anyway, so without this the thumbnail just wouldn't be clickable at all.
 *
 * size="row" renders a small, list-row-sized thumbnail (e.g. search results) instead of the
 * larger default used on the document detail page — the click-to-enlarge modal is unchanged.
 * In row mode, `children` (e.g. the filename/meta) render next to the thumbnail inside the
 * same clickable area, so the name opens the preview too, not just the thumbnail icon.
 */
export function DocumentPreview({
  tenantId,
  documentId,
  contentType,
  filename,
  size = "detail",
  children,
}: {
  tenantId: string;
  documentId: string;
  contentType: string | null;
  filename: string;
  size?: "detail" | "row";
  children?: ReactNode;
}) {
  const isImage = contentType?.startsWith("image/") ?? false;
  const isPdf = contentType === "application/pdf";
  const isText = contentType?.startsWith("text/") ?? false;
  const previewable = isImage || isPdf || isText;

  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [expanded, setExpanded] = useState(false);

  useEffect(() => {
    if (!previewable) return;
    let cancelled = false;
    let objectUrl: string | null = null;
    setBlobUrl(null);
    setFailed(false);

    apiClient
      .get(`/tenants/${tenantId}/documents/${documentId}/download`, { responseType: "blob" })
      .then((res) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(res.data as Blob);
        setBlobUrl(objectUrl);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });

    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [tenantId, documentId, previewable]);

  const modal = expanded && blobUrl && (
    <div className="doc-preview-modal-backdrop" onClick={() => setExpanded(false)}>
      <div className="doc-preview-modal" onClick={(e) => e.stopPropagation()}>
        <div className="doc-preview-modal-bar">
          <button className="icon-btn secondary" title="Close" onClick={() => setExpanded(false)}>
            ×
          </button>
        </div>
        {isImage ? (
          <img src={blobUrl} alt={filename} className="doc-preview-modal-media doc-preview-modal-img" />
        ) : (
          <iframe src={blobUrl} title={filename} className="doc-preview-modal-media doc-preview-modal-frame" />
        )}
      </div>
    </div>
  );

  if (size === "row") {
    const canEnlarge = previewable && !!blobUrl;
    // PDF/text at icon size would just show an unreadable sliver of viewer chrome, so use
    // the generic icon for those too — still clickable, since the file itself previews fine
    // at full size, just not meaningfully thumbnailable this small.
    const thumb =
      isImage && blobUrl ? (
        <div className="doc-preview-thumb-row">
          <img src={blobUrl} alt={filename} className="doc-preview-media" />
        </div>
      ) : (
        <div className="item-bar-icon neutral">
          <DocumentIcon />
        </div>
      );

    return (
      <>
        <div
          className="doc-preview-row-trigger"
          onClick={canEnlarge ? () => setExpanded(true) : undefined}
          style={{ cursor: canEnlarge ? "pointer" : "default" }}
          title={canEnlarge ? "Click to preview" : undefined}
        >
          {thumb}
          {children}
        </div>
        {modal}
      </>
    );
  }

  if (!previewable) {
    return (
      <div className="doc-preview doc-preview-empty">
        <p className="muted">Preview isn't available for this file type — use Download to view it.</p>
      </div>
    );
  }
  if (failed) {
    return (
      <div className="doc-preview doc-preview-empty">
        <p className="muted">Couldn't load a preview.</p>
      </div>
    );
  }
  if (!blobUrl) {
    return (
      <div className="doc-preview doc-preview-empty">
        <p className="muted">Loading preview…</p>
      </div>
    );
  }

  return (
    <>
      <div className="doc-preview doc-preview-thumb" onClick={() => setExpanded(true)} title="Click to enlarge">
        {isImage ? (
          <img src={blobUrl} alt={filename} className="doc-preview-media" />
        ) : (
          <iframe src={blobUrl} title={filename} className="doc-preview-media" style={{ pointerEvents: "none" }} />
        )}
      </div>
      {modal}
    </>
  );
}
