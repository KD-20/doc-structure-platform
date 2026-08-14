import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { apiClient, errorMessage } from "../api/client";
import type { EffectiveRuleSet, RuleSet } from "../api/types";
import { RuleIcon } from "../components/icons";
import { useAuth } from "../auth/AuthContext";
import { isAdminRole } from "../auth/roles";
import { fixedPanelPositionBelow, type FixedPosition } from "../utils/floatingPosition";

export function RuleSetsPage() {
  const { tenantId } = useParams();
  const { role } = useAuth();
  // Mirrors the backend: creating/saving rule set versions needs ADMIN (RuleSetController's
  // PUT/activate). Both "View" and "Edit" open the same editor page regardless of role — it
  // already disables every mutating control for non-admins itself — but they're kept as two
  // separate buttons rather than one combined label ("Edit / view"), since a viewer opening the
  // page to inspect a rule set and an admin opening it to change something are different
  // intents even when they land on the same URL. Non-admins only ever see "View".
  const isAdmin = isAdminRole(role);
  const [effective, setEffective] = useState<EffectiveRuleSet[]>([]);
  const [ruleSets, setRuleSets] = useState<RuleSet[]>([]);
  const [error, setError] = useState<string | null>(null);

  const [openHistoryDocType, setOpenHistoryDocType] = useState<string | null>(null);
  const [historyPanelPos, setHistoryPanelPos] = useState<FixedPosition | null>(null);

  useEffect(() => {
    apiClient
      .get<EffectiveRuleSet[]>(`/tenants/${tenantId}/rule-sets/effective`)
      .then((res) => setEffective(res.data))
      .catch((err) => setError(errorMessage(err)));
    apiClient
      .get<RuleSet[]>(`/tenants/${tenantId}/rule-sets`)
      .then((res) => setRuleSets(res.data))
      .catch((err) => setError(errorMessage(err)));
  }, [tenantId]);

  function closeHistoryDropdown() {
    setOpenHistoryDocType(null);
    setHistoryPanelPos(null);
  }

  useEffect(() => {
    function closeOnOutsideClick(e: MouseEvent) {
      if (!(e.target as Element).closest(".history-dropdown")) {
        closeHistoryDropdown();
      }
    }
    document.addEventListener("mousedown", closeOnOutsideClick);
    return () => document.removeEventListener("mousedown", closeOnOutsideClick);
  }, []);

  // Same fixed-position-escapes-the-scroll-wrapper-clipping approach as the Documents and Audit
  // Log pages' history dropdowns (see utils/floatingPosition.ts) — this table sits in the same
  // kind of overflowX-scrollable wrapper, which would otherwise clip the panel the same way.
  useEffect(() => {
    if (!openHistoryDocType) return;
    window.addEventListener("scroll", closeHistoryDropdown, true);
    return () => window.removeEventListener("scroll", closeHistoryDropdown, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openHistoryDocType]);

  const byDocType = new Map<string, RuleSet[]>();
  for (const rs of ruleSets) {
    byDocType.set(rs.docType, [...(byDocType.get(rs.docType) ?? []), rs]);
  }

  function editUrl(docType: string, opts?: { version?: number; fromDefault?: boolean; view?: boolean }) {
    const params = new URLSearchParams();
    if (opts?.version) params.set("version", String(opts.version));
    if (opts?.fromDefault) params.set("fromDefault", "1");
    // "View" opens a plain read-only display (field list + JSON) with no add-field/save/activate
    // controls at all — not just the same edit form with inputs disabled. "Edit"/"Customize"
    // omit this and get the full editor (still role-gated by canEdit inside it as before).
    if (opts?.view) params.set("mode", "view");
    const qs = params.toString();
    return `/t/${tenantId}/rule-sets/${docType}/edit${qs ? `?${qs}` : ""}`;
  }

  return (
    <div>
      <div className="topbar">
        <h1>Rule Sets</h1>
        {isAdmin && (
          <Link to={`/t/${tenantId}/rule-sets/new/edit`}>
            <button>New rule set</button>
          </Link>
        )}
      </div>
      {error && <div className="error-banner">{error}</div>}

      <div className="card">
        <h2 style={{ fontSize: 15, marginTop: 0 }}>Document types</h2>
        <p className="muted" style={{ marginTop: 0 }}>
          Every type below already extracts structured data — built-in types use a default template
          until you customize them. No setup is required to start uploading.
        </p>

        <div style={{ overflowX: "auto" }}>
          {/* table-layout: fixed + explicit column widths, not the default auto-sizing — with
              auto layout, one row's longer content (a long doc type name, a "5 versions"
              button vs plain "No custom versions" text) can shift where that SAME column starts
              on other rows, since auto layout doesn't guarantee every column is pinned to one
              width independent of content. Fixed layout does: every column is exactly this wide
              on every row, so the actions column's buttons always start at the same x. */}
          <table style={{ tableLayout: "fixed" }}>
            <colgroup>
              <col style={{ width: "16%" }} />
              <col style={{ width: "32%" }} />
              <col style={{ width: "12%" }} />
              <col style={{ width: "16%" }} />
              <col style={{ width: "12%" }} />
              <col style={{ width: "12%" }} />
            </colgroup>
            <thead>
              <tr>
                <th>Rule set</th>
                <th>Fields</th>
                <th>Status</th>
                <th>History</th>
                <th className="col-actions">View</th>
                <th className="col-actions">Edit</th>
              </tr>
            </thead>
            <tbody>
              {effective.map((e) => {
                const versions = (byDocType.get(e.docType) ?? []).sort((a, b) => b.version - a.version);
                const isHistoryOpen = openHistoryDocType === e.docType;
                return (
                  <tr key={e.docType}>
                    <td>
                      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                        <div className="item-bar-icon neutral">
                          <RuleIcon />
                        </div>
                        <span className="item-bar-title" style={{ whiteSpace: "normal", overflowWrap: "anywhere" }}>
                          {e.docType}
                        </span>
                      </div>
                    </td>
                    <td className="muted" style={{ overflowWrap: "anywhere" }}>
                      {e.definition.fields.map((f) => f.name).join(", ") || "—"}
                    </td>
                    <td>
                      {e.source === "CUSTOM" ? (
                        <span className="badge">custom v{e.activeVersion}</span>
                      ) : (
                        <span className="status-pill status-neutral">default template</span>
                      )}
                    </td>
                    <td>
                      {versions.length > 0 ? (
                        <div className="history-dropdown">
                          <button
                            type="button"
                            className="history-dropdown-toggle"
                            onClick={(ev) => {
                              if (isHistoryOpen) {
                                closeHistoryDropdown();
                                return;
                              }
                              setOpenHistoryDocType(e.docType);
                              setHistoryPanelPos(fixedPanelPositionBelow(ev.currentTarget));
                            }}
                          >
                            {versions.length} version{versions.length === 1 ? "" : "s"} {isHistoryOpen ? "▲" : "▾"}
                          </button>
                          {isHistoryOpen && historyPanelPos && (
                            <div
                              className="history-dropdown-panel"
                              style={{ position: "fixed", top: historyPanelPos.top, right: historyPanelPos.right }}
                            >
                              <div className="history-dropdown-panel-header">
                                <span className="muted" style={{ fontSize: 12 }}>
                                  Version history
                                </span>
                                <button className="icon-btn secondary" title="Close" onClick={closeHistoryDropdown}>
                                  ×
                                </button>
                              </div>
                              {versions.map((rs) => (
                                <div className="history-dropdown-row" key={rs.id}>
                                  <span style={{ display: "flex", alignItems: "center", gap: 6 }}>
                                    v{rs.version}
                                    {rs.active && <span className="badge">active</span>}
                                  </span>
                                  <span style={{ display: "flex", gap: 4 }}>
                                    <Link to={editUrl(e.docType, { version: rs.version, view: true })}>
                                      <button className="secondary small">View</button>
                                    </Link>
                                    {isAdmin && (
                                      <Link to={editUrl(e.docType, { version: rs.version })}>
                                        <button className="small">Edit</button>
                                      </Link>
                                    )}
                                  </span>
                                </div>
                              ))}
                            </div>
                          )}
                        </div>
                      ) : (
                        <span className="muted history-empty-placeholder">No custom versions</span>
                      )}
                    </td>
                    <td className="col-actions">
                      <Link to={editUrl(e.docType, { fromDefault: e.source === "DEFAULT", view: true })}>
                        <button className="secondary small">View</button>
                      </Link>
                    </td>
                    <td className="col-actions">
                      {isAdmin && (
                        <Link to={editUrl(e.docType, { fromDefault: e.source === "DEFAULT" })}>
                          <button className="small">{e.source === "CUSTOM" ? "Edit" : "Customize"}</button>
                        </Link>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        {effective.length === 0 && <p className="muted">No document types available yet.</p>}
      </div>
    </div>
  );
}
