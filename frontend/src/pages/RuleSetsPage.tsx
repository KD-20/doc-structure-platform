import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { apiClient, errorMessage } from "../api/client";
import type { EffectiveRuleSet, RuleSet } from "../api/types";
import { RuleIcon } from "../components/icons";
import { useAuth } from "../auth/AuthContext";
import { isAdminRole } from "../auth/roles";

export function RuleSetsPage() {
  const { tenantId } = useParams();
  const { role } = useAuth();
  // Mirrors the backend: creating/saving rule set versions needs ADMIN (RuleSetController's
  // PUT/activate) — "New rule set" has no non-misleading equivalent for anyone below that, so
  // it's hidden rather than disabled; the per-row links stay navigable either way (still lets a
  // non-admin see a rule set's field configuration) but relabel to "View" instead of
  // "Edit"/"Customize", since RuleSetEditorPage itself disables every mutating control for them.
  const isAdmin = isAdminRole(role);
  const [effective, setEffective] = useState<EffectiveRuleSet[]>([]);
  const [ruleSets, setRuleSets] = useState<RuleSet[]>([]);
  const [error, setError] = useState<string | null>(null);

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

  const byDocType = new Map<string, RuleSet[]>();
  for (const rs of ruleSets) {
    byDocType.set(rs.docType, [...(byDocType.get(rs.docType) ?? []), rs]);
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
        <div className="item-bar-list">
          {effective.map((e) => (
            <div className="item-bar static" key={e.docType}>
              <div className="item-bar-icon neutral">
                <RuleIcon />
              </div>
              <div className="item-bar-main">
                <span className="item-bar-title">{e.docType}</span>
                <div className="item-bar-meta">{e.definition.fields.map((f) => f.name).join(", ")}</div>
              </div>
              <div className="item-bar-right">
                {e.source === "CUSTOM" ? (
                  <span className="badge">custom v{e.activeVersion}</span>
                ) : (
                  <span className="status-pill status-neutral">default template</span>
                )}
                {e.source === "CUSTOM" ? (
                  <Link to={`/t/${tenantId}/rule-sets/${e.docType}/edit`}>
                    <button className="secondary small">{isAdmin ? "Edit" : "View"}</button>
                  </Link>
                ) : (
                  <Link to={`/t/${tenantId}/rule-sets/${e.docType}/edit?fromDefault=1`}>
                    <button className="secondary small">{isAdmin ? "Customize" : "View"}</button>
                  </Link>
                )}
              </div>
            </div>
          ))}
        </div>
        {effective.length === 0 && <p className="muted">No document types available yet.</p>}
      </div>

      {[...byDocType.entries()].map(([docType, versions]) => (
        <div className="card" key={docType}>
          <h2 style={{ fontSize: 15, marginTop: 0 }}>{docType} — version history</h2>
          <div className="item-bar-list">
            {versions
              .sort((a, b) => b.version - a.version)
              .map((rs) => (
                <div className="item-bar static" key={rs.id}>
                  <div className="item-bar-main">
                    <span className="item-bar-title">v{rs.version}</span>
                    <div className="item-bar-meta">{rs.definition.fields.map((f) => f.name).join(", ")}</div>
                  </div>
                  <div className="item-bar-right">
                    {rs.active && <span className="badge">active</span>}
                    <Link to={`/t/${tenantId}/rule-sets/${docType}/edit?version=${rs.version}`}>
                      <button className="secondary small">{isAdmin ? "Edit / view" : "View"}</button>
                    </Link>
                  </div>
                </div>
              ))}
          </div>
        </div>
      ))}
    </div>
  );
}
