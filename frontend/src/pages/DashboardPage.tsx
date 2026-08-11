import { useEffect, useState, type ReactNode } from "react";
import { Link, useParams } from "react-router-dom";
import { apiClient } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { isEditorRole } from "../auth/roles";
import type { DocumentSummary, GuestLink, Page, RuleSet } from "../api/types";
import { ClipboardIcon, DocumentIcon, RuleIcon, SearchIcon, ShareIcon, UsersIcon } from "../components/icons";

const ADMIN_ROLES = new Set(["OWNER", "ADMIN"]);

interface Tile {
  to: string;
  icon: ReactNode;
  title: string;
  description: string;
  stat?: string;
  adminOnly?: boolean;
}

export function DashboardPage() {
  const { tenantId } = useParams();
  const { tenantName, role, email } = useAuth();
  const base = `/t/${tenantId}`;
  const isAdmin = role !== null && ADMIN_ROLES.has(role);
  // Mirrors the backend: document upload needs EDITOR (DocumentController's POST) — the
  // welcome banner's shortcut button isn't part of the tile-gating list below, so it needs its
  // own check (missed the first time around, see chat history).
  const canUpload = isEditorRole(role);

  const [documentCount, setDocumentCount] = useState<number | null>(null);
  const [ruleSetCount, setRuleSetCount] = useState<number | null>(null);
  const [guestLinkCount, setGuestLinkCount] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;

    apiClient
      .get<Page<DocumentSummary>>(`/tenants/${tenantId}/documents`, { params: { size: 1 } })
      .then((res) => !cancelled && setDocumentCount(res.data.totalElements))
      .catch(() => !cancelled && setDocumentCount(null));

    apiClient
      .get<RuleSet[]>(`/tenants/${tenantId}/rule-sets`)
      .then((res) => !cancelled && setRuleSetCount(new Set(res.data.map((r) => r.docType)).size))
      .catch(() => !cancelled && setRuleSetCount(null));

    if (isAdmin) {
      apiClient
        .get<GuestLink[]>(`/tenants/${tenantId}/guest-links`)
        .then((res) => !cancelled && setGuestLinkCount(res.data.filter((l) => !l.revoked).length))
        .catch(() => !cancelled && setGuestLinkCount(null));
    }

    return () => {
      cancelled = true;
    };
  }, [tenantId, isAdmin]);

  const tiles: Tile[] = [
    {
      to: `${base}/documents`,
      icon: <DocumentIcon />,
      title: "Documents",
      description: "Upload files and review extraction status",
      stat: documentCount !== null ? `${documentCount} uploaded` : undefined,
    },
    {
      to: `${base}/rule-sets`,
      icon: <RuleIcon />,
      title: "Rule Sets",
      description: "Define how each document type gets structured",
      stat: ruleSetCount !== null ? `${ruleSetCount} doc type${ruleSetCount === 1 ? "" : "s"}` : undefined,
    },
    {
      to: `${base}/search`,
      icon: <SearchIcon />,
      title: "Search",
      description: "Full-text and structured field search",
    },
    {
      to: `${base}/settings/members`,
      icon: <UsersIcon />,
      title: "Members",
      description: "Manage who has access and their role",
      adminOnly: true,
    },
    {
      to: `${base}/settings/guest-links`,
      icon: <ShareIcon />,
      title: "Guest Links",
      description: "Share documents externally, no account needed",
      stat: guestLinkCount !== null ? `${guestLinkCount} active` : undefined,
      adminOnly: true,
    },
    {
      to: `${base}/audit-log`,
      icon: <ClipboardIcon />,
      title: "Audit Log",
      description: "Every upload, extraction, and role change",
      adminOnly: true,
    },
  ];

  const visibleTiles = tiles.filter((t) => !t.adminOnly || isAdmin);

  return (
    <div>
      <div className="welcome-banner">
        <div>
          <h1>Welcome back{email ? `, ${email.split("@")[0]}` : ""}</h1>
          <p>
            {tenantName ?? "Your tenant"} · <span className="badge">{role}</span>
          </p>
        </div>
        {canUpload && (
          <Link to={`${base}/documents`}>
            <button className="pill">Upload a document</button>
          </Link>
        )}
      </div>

      <div className="section-label">Workspace</div>
      <div className="tile-grid">
        {visibleTiles.map((tile) => (
          <Link to={tile.to} className="tile" key={tile.to}>
            <div className="tile-top">
              <div className="tile-icon">{tile.icon}</div>
              {tile.stat && <span className="tile-stat">{tile.stat}</span>}
            </div>
            <div className="tile-title">{tile.title}</div>
            <p className="tile-desc">{tile.description}</p>
          </Link>
        ))}
      </div>
    </div>
  );
}
