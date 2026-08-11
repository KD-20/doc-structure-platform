import { useEffect, useState, type FormEvent } from "react";
import { useParams } from "react-router-dom";
import { apiClient, errorMessage } from "../api/client";
import type { DocumentSummary, GuestLink, Page } from "../api/types";
import { ShareIcon } from "../components/icons";
import { useAuth } from "../auth/AuthContext";
import { isAdminRole } from "../auth/roles";

function defaultExpiry(): string {
  const d = new Date();
  d.setDate(d.getDate() + 7);
  return d.toISOString().slice(0, 16);
}

// Small on purpose: the picker used to fetch up to 100 documents in one request and render
// them all as chips, which breaks down (silently truncates past 100, and is a lot of DOM for
// a picker) for tenants with large document counts. Fetching a real page at a time and
// letting the admin fetch more on demand scales to any number of documents.
const DOCS_PAGE_SIZE = 5;

export function GuestLinksPage() {
  const { tenantId } = useParams();
  const { role } = useAuth();
  // Nav hides this page's link for non-admins already (see Layout), but that doesn't stop
  // direct URL navigation. Unlike Members, even *listing* guest links requires ADMIN on the
  // backend (GuestLinkController's GET, not just POST/DELETE) — nothing on this page works for
  // anyone below that, so the whole page is gated rather than individual controls.
  const isAdmin = isAdminRole(role);
  const [links, setLinks] = useState<GuestLink[]>([]);
  const [documents, setDocuments] = useState<DocumentSummary[]>([]);
  const [docsPage, setDocsPage] = useState(0);
  const [docsTotalPages, setDocsTotalPages] = useState(1);
  const [loadingMoreDocs, setLoadingMoreDocs] = useState(false);
  const [selectedDocIds, setSelectedDocIds] = useState<Set<string>>(new Set());
  const [expiresAt, setExpiresAt] = useState(defaultExpiry());
  const [maxUses, setMaxUses] = useState("");
  const [notifyEmail, setNotifyEmail] = useState("");
  const [newToken, setNewToken] = useState<string | null>(null);
  const [newTokenDocId, setNewTokenDocId] = useState<string | null>(null);
  const [emailResult, setEmailResult] = useState<{ sent: boolean; error: string | null } | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function loadLinks() {
    try {
      const { data } = await apiClient.get<GuestLink[]>(`/tenants/${tenantId}/guest-links`);
      setLinks(data);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function loadDocuments(pageToLoad: number) {
    setLoadingMoreDocs(true);
    try {
      const { data } = await apiClient.get<Page<DocumentSummary>>(`/tenants/${tenantId}/documents`, {
        params: { page: pageToLoad, size: DOCS_PAGE_SIZE },
      });
      setDocuments((prev) => (pageToLoad === 0 ? data.content : [...prev, ...data.content]));
      setDocsPage(data.number);
      setDocsTotalPages(data.totalPages);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoadingMoreDocs(false);
    }
  }

  useEffect(() => {
    if (!isAdmin) return;
    loadLinks();
    loadDocuments(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenantId, isAdmin]);

  function toggleDoc(id: string) {
    const next = new Set(selectedDocIds);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setSelectedDocIds(next);
  }

  async function handleCreate(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setNewToken(null);
    setEmailResult(null);
    try {
      const { data } = await apiClient.post<GuestLink>(`/tenants/${tenantId}/guest-links`, {
        documentIds: [...selectedDocIds],
        expiresAt: new Date(expiresAt).toISOString(),
        maxUses: maxUses ? Number(maxUses) : null,
        notifyEmail: notifyEmail.trim() || undefined,
      });
      setNewToken(data.token);
      setNewTokenDocId([...selectedDocIds][0] ?? null);
      // emailSent is only non-null when a notifyEmail was actually sent with the request —
      // the link is always created either way, so a failed/unconfigured send just falls back
      // to the copyable token/URL shown below, never blocks link creation.
      if (data.emailSent !== null) {
        setEmailResult({ sent: data.emailSent, error: data.emailError });
      }
      setSelectedDocIds(new Set());
      setNotifyEmail("");
      await loadLinks();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function handleRevoke(linkId: string) {
    setError(null);
    try {
      await apiClient.delete(`/tenants/${tenantId}/guest-links/${linkId}`);
      await loadLinks();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  const guestUrl = newToken ? `${window.location.origin}/guest/${newToken}/documents/${newTokenDocId ?? ""}` : null;
  const hasMoreDocs = docsPage + 1 < docsTotalPages;

  if (!isAdmin) {
    return (
      <div>
        <h1>Guest Links</h1>
        <p className="muted">Guest links are only visible to admins.</p>
      </div>
    );
  }

  return (
    <div>
      <h1>Guest Links</h1>
      {error && <div className="error-banner">{error}</div>}
      {newToken && (
        <div className="card" style={{ borderColor: "var(--success)" }}>
          <strong>Guest link created.</strong> This token is shown only once — copy it now.
          <pre className="json-view">{newToken}</pre>
          {guestUrl && <p className="muted">Example URL: {guestUrl}</p>}
          {emailResult &&
            (emailResult.sent ? (
              <p style={{ color: "var(--success)" }}>✓ Emailed.</p>
            ) : (
              <p style={{ color: "var(--danger)" }}>
                Not emailed — {emailResult.error ?? "unknown error"}. Use the link above instead.
              </p>
            ))}
        </div>
      )}

      <div className="card">
        <h2 style={{ fontSize: 15, marginTop: 0 }}>Create a guest link</h2>
        <form onSubmit={handleCreate}>
          <label style={{ fontSize: 13, color: "var(--muted)" }}>Documents to share</label>
          <div className="chip-list">
            {documents.map((d) => (
              <span
                key={d.id}
                className={`chip${selectedDocIds.has(d.id) ? " selected" : ""}`}
                onClick={() => toggleDoc(d.id)}
              >
                {d.filename}
              </span>
            ))}
            {hasMoreDocs && (
              <span
                className="chip chip-more"
                onClick={() => !loadingMoreDocs && loadDocuments(docsPage + 1)}
                title="Load more documents"
              >
                {loadingMoreDocs ? "loading…" : "···"}
              </span>
            )}
            {documents.length === 0 && !loadingMoreDocs && (
              <span className="muted">No documents to share yet — upload one first.</span>
            )}
          </div>
          <div style={{ display: "flex", gap: 16, flexWrap: "wrap" }}>
            <div className="form-row" style={{ maxWidth: 260 }}>
              <label>Expires at</label>
              <input type="datetime-local" value={expiresAt} onChange={(e) => setExpiresAt(e.target.value)} required />
            </div>
            <div className="form-row" style={{ maxWidth: 160 }}>
              <label>Max uses (optional)</label>
              <input type="number" min={1} value={maxUses} onChange={(e) => setMaxUses(e.target.value)} />
            </div>
            <div className="form-row" style={{ maxWidth: 260 }}>
              <label>Email it to (optional)</label>
              <input
                type="email"
                placeholder="someone@example.com"
                value={notifyEmail}
                onChange={(e) => setNotifyEmail(e.target.value)}
              />
            </div>
          </div>
          <button type="submit" className="pill" disabled={selectedDocIds.size === 0}>
            {notifyEmail.trim() ? "Create and email guest link" : "Create guest link"}
          </button>
        </form>
      </div>

      <div className="card">
        <div className="item-bar-list">
          {links.map((l) => (
            <div className="item-bar static" key={l.id}>
              <div className="item-bar-icon">
                <ShareIcon />
              </div>
              <div className="item-bar-main">
                <div className="item-bar-title">
                  {l.documentIds.length} document{l.documentIds.length === 1 ? "" : "s"}
                </div>
                <div className="item-bar-meta">
                  Expires {new Date(l.expiresAt).toLocaleString()} · {l.useCount}
                  {l.maxUses ? ` / ${l.maxUses}` : ""} uses
                </div>
              </div>
              <div className="item-bar-right">
                {l.revoked ? (
                  <span className="status-pill status-error">revoked</span>
                ) : (
                  <>
                    <span className="status-pill status-ok">active</span>
                    <button className="danger" onClick={() => handleRevoke(l.id)}>
                      Revoke
                    </button>
                  </>
                )}
              </div>
            </div>
          ))}
        </div>
        {links.length === 0 && <p className="muted">No guest links yet.</p>}
      </div>
    </div>
  );
}
