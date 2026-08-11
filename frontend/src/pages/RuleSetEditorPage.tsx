import { useEffect, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { apiClient, errorMessage } from "../api/client";
import type { EffectiveRuleSet, FieldRule, InterpretedField, NormalizerSpec, RuleSet, RuleSetDefinition } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { isAdminRole, isEditorRole } from "../auth/roles";

type SimpleFieldType = "text" | "date" | "currency";

const TEMPLATE: RuleSetDefinition = {
  docType: "invoice",
  fields: [
    {
      name: "invoice_number",
      type: "string",
      required: true,
      strategy: "ANCHOR_REGEX",
      params: { anchorText: "Invoice Number:", searchWindowChars: 80, pattern: "([A-Z]{2,4}-\\d{4}-\\d{4,8})" },
      normalizer: null,
    },
  ],
};

// The three "kinds" the simple editor knows how to render as plain inputs. Anything else
// (REGEX_GLOBAL, or an ANCHOR_REGEX with a hand-written pattern) still shows up — just as a
// read-only note pointing at Advanced (JSON) — rather than being hidden or silently dropped.
function fieldKind(field: FieldRule): "anchor" | "table" | "advanced" {
  if (field.strategy === "TABLE_UNDER_HEADING") return "table";
  if (field.strategy === "ANCHOR_REGEX") return "anchor";
  return "advanced";
}

function detectSimpleType(field: FieldRule): SimpleFieldType {
  if (field.normalizer?.type === "DATE") return "date";
  if (field.normalizer?.type === "CURRENCY") return "currency";
  return "text";
}

function defaultsForType(type: SimpleFieldType): { pattern: string; normalizer: NormalizerSpec | null; fieldType: string } {
  switch (type) {
    case "date":
      return {
        pattern: "(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})",
        normalizer: { type: "DATE", params: { inputFormats: ["MM/dd/yyyy", "dd-MM-yyyy", "dd/MM/yyyy"] } },
        fieldType: "date",
      };
    case "currency":
      return {
        pattern: "\\$?([\\d,]+\\.?\\d{0,2})",
        normalizer: { type: "CURRENCY", params: { stripSymbols: true } },
        fieldType: "decimal",
      };
    default:
      return { pattern: "([^\\n]{1,80})", normalizer: null, fieldType: "string" };
  }
}

function newAnchorField(): FieldRule {
  const { pattern, normalizer, fieldType } = defaultsForType("text");
  return {
    name: "",
    type: fieldType,
    required: false,
    strategy: "ANCHOR_REGEX",
    params: { anchorText: "", searchWindowChars: 80, pattern },
    normalizer,
  };
}

function newTableField(): FieldRule {
  return {
    name: "",
    type: "table",
    required: false,
    strategy: "TABLE_UNDER_HEADING",
    params: { headingText: "", columns: [] },
    normalizer: null,
  };
}

export function RuleSetEditorPage() {
  const { tenantId, docType } = useParams();
  const [searchParams] = useSearchParams();
  const version = searchParams.get("version");
  const fromDefault = searchParams.get("fromDefault") === "1";
  const navigate = useNavigate();
  const isNew = docType === "new";
  const { role } = useAuth();
  // Mirrors the backend exactly: saving/activating a rule set needs ADMIN
  // (RuleSetController's PUT/activate), previewing only needs EDITOR (its /preview endpoint).
  // Inputs stay visible but disabled rather than hidden below "canEdit" gates, so a VIEWER (or
  // an EDITOR, who can preview but not save) can still see the current field configuration —
  // just without any control that would 403 if clicked.
  const canEdit = isAdminRole(role);
  const canPreview = isEditorRole(role);

  const [definition, setDefinition] = useState<RuleSetDefinition>(TEMPLATE);
  const [mode, setMode] = useState<"simple" | "json">("simple");
  const [jsonDraft, setJsonDraft] = useState("");
  const [active, setActive] = useState(false);
  const [sampleText, setSampleText] = useState("");
  const [previewResult, setPreviewResult] = useState<InterpretedField[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (isNew) return;
    if (fromDefault) {
      // No tenant version exists yet for this doc type — pull the built-in default's
      // definition as a starting point instead of hitting /active, which would 404.
      apiClient
        .get<EffectiveRuleSet[]>(`/tenants/${tenantId}/rule-sets/effective`)
        .then((res) => {
          const match = res.data.find((e) => e.docType === docType);
          if (match) setDefinition(match.definition);
        })
        .catch((err) => setError(errorMessage(err)));
      return;
    }
    const url = version
      ? `/tenants/${tenantId}/rule-sets/${docType}/versions/${version}`
      : `/tenants/${tenantId}/rule-sets/${docType}/active`;
    apiClient
      .get<RuleSet>(url)
      .then((res) => {
        setDefinition(res.data.definition);
        setActive(res.data.active);
      })
      .catch((err) => setError(errorMessage(err)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenantId, docType, version, isNew, fromDefault]);

  function updateField(index: number, patch: Partial<FieldRule>) {
    setDefinition((prev) => ({
      ...prev,
      fields: prev.fields.map((f, i) => (i === index ? { ...f, ...patch } : f)),
    }));
  }

  function updateFieldParams(index: number, patch: Record<string, unknown>) {
    setDefinition((prev) => ({
      ...prev,
      fields: prev.fields.map((f, i) => (i === index ? { ...f, params: { ...f.params, ...patch } } : f)),
    }));
  }

  function changeFieldType(index: number, type: SimpleFieldType) {
    const { pattern, normalizer, fieldType } = defaultsForType(type);
    updateField(index, { type: fieldType, normalizer, params: { ...definition.fields[index].params, pattern } });
  }

  function removeField(index: number) {
    setDefinition((prev) => ({ ...prev, fields: prev.fields.filter((_, i) => i !== index) }));
  }

  function addAnchorField() {
    setDefinition((prev) => ({ ...prev, fields: [...prev.fields, newAnchorField()] }));
  }

  function addTableField() {
    setDefinition((prev) => ({ ...prev, fields: [...prev.fields, newTableField()] }));
  }

  function openJsonMode() {
    setJsonDraft(JSON.stringify(definition, null, 2));
    setMode("json");
  }

  function applyJsonAndReturnToSimple() {
    try {
      const parsed = JSON.parse(jsonDraft) as RuleSetDefinition;
      setDefinition(parsed);
      setMode("simple");
      setError(null);
    } catch {
      setError("Definition is not valid JSON — fix it or it won't save.");
    }
  }

  function currentDefinition(): RuleSetDefinition | null {
    if (mode === "simple") return definition;
    try {
      return JSON.parse(jsonDraft) as RuleSetDefinition;
    } catch {
      setError("Definition is not valid JSON");
      return null;
    }
  }

  async function handlePreview() {
    const def = currentDefinition();
    if (!def || !sampleText.trim()) return;
    setError(null);
    try {
      const { data } = await apiClient.post<{ fields: InterpretedField[] }>(
        `/tenants/${tenantId}/rule-sets/preview`,
        { definition: def, sampleText },
      );
      setPreviewResult(data.fields);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function handleSave() {
    const def = currentDefinition();
    if (!def) return;
    if (!def.docType.trim()) {
      setError("Document type is required.");
      return;
    }
    setError(null);
    setSaving(true);
    try {
      await apiClient.put(`/tenants/${tenantId}/rule-sets/${def.docType}`, { definition: def });
      navigate(`/t/${tenantId}/rule-sets`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  async function handleActivate() {
    if (!version) return;
    setError(null);
    try {
      await apiClient.post(`/tenants/${tenantId}/rule-sets/${docType}/versions/${version}/activate`);
      setActive(true);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <div>
      <h1>{isNew ? "New rule set" : `${docType} — ${fromDefault ? "customizing default" : `v${version ?? "active"}`}`}</h1>
      {error && <div className="error-banner">{error}</div>}

      <div className="card">
        <p className="muted">
          {fromDefault
            ? "Starting from the platform's built-in template for this doc type. Saving creates your tenant's own v1 — from then on, it's used instead of the default."
            : "For each field, give it a name and the label it appears next to in the document (e.g. \"Invoice Number:\"). Saving always creates a new version and makes it active."}
        </p>

        {mode === "simple" ? (
          <>
            {isNew && (
              <div className="form-row" style={{ maxWidth: 260 }}>
                <label>Document type</label>
                <input
                  value={definition.docType}
                  onChange={(e) => setDefinition((prev) => ({ ...prev, docType: e.target.value }))}
                  placeholder="e.g. invoice"
                  disabled={!canEdit}
                />
              </div>
            )}

            <div className="rule-field-list">
              <div className="rule-field-row rule-field-header">
                <span>Field name</span>
                <span>Label to find it near</span>
                <span>Type</span>
                <span>Required</span>
                <span></span>
              </div>
              {definition.fields.map((field, i) => {
                const kind = fieldKind(field);
                if (kind === "anchor") {
                  return (
                    <div className="rule-field-row" key={i}>
                      <input
                        value={field.name}
                        onChange={(e) => updateField(i, { name: e.target.value })}
                        placeholder="e.g. invoice_number"
                        disabled={!canEdit}
                      />
                      <input
                        value={(field.params.anchorText as string) ?? ""}
                        onChange={(e) => updateFieldParams(i, { anchorText: e.target.value })}
                        placeholder="e.g. Invoice Number:"
                        disabled={!canEdit}
                      />
                      <select
                        value={detectSimpleType(field)}
                        onChange={(e) => changeFieldType(i, e.target.value as SimpleFieldType)}
                        disabled={!canEdit}
                      >
                        <option value="text">Text</option>
                        <option value="date">Date</option>
                        <option value="currency">Currency</option>
                      </select>
                      <input
                        type="checkbox"
                        checked={field.required}
                        onChange={(e) => updateField(i, { required: e.target.checked })}
                        disabled={!canEdit}
                      />
                      <button
                        type="button"
                        className="danger icon-btn"
                        onClick={() => removeField(i)}
                        disabled={!canEdit}
                      >
                        ×
                      </button>
                    </div>
                  );
                }
                if (kind === "table") {
                  return (
                    <div className="rule-field-row rule-field-row-table" key={i}>
                      <input
                        value={field.name}
                        onChange={(e) => updateField(i, { name: e.target.value })}
                        placeholder="e.g. line_items"
                        disabled={!canEdit}
                      />
                      <input
                        value={(field.params.headingText as string) ?? ""}
                        onChange={(e) => updateFieldParams(i, { headingText: e.target.value })}
                        placeholder="Section heading, e.g. Line Items"
                        disabled={!canEdit}
                      />
                      <input
                        value={((field.params.columns as string[]) ?? []).join(", ")}
                        onChange={(e) =>
                          updateFieldParams(i, {
                            columns: e.target.value.split(",").map((c) => c.trim()).filter(Boolean),
                          })
                        }
                        placeholder="Columns: description, qty, amount"
                        disabled={!canEdit}
                      />
                      <input
                        type="checkbox"
                        checked={field.required}
                        onChange={(e) => updateField(i, { required: e.target.checked })}
                        disabled={!canEdit}
                      />
                      <button
                        type="button"
                        className="danger icon-btn"
                        onClick={() => removeField(i)}
                        disabled={!canEdit}
                      >
                        ×
                      </button>
                    </div>
                  );
                }
                return (
                  <div className="rule-field-row" key={i}>
                    <input
                      value={field.name}
                      onChange={(e) => updateField(i, { name: e.target.value })}
                      disabled={!canEdit}
                    />
                    <span className="muted" style={{ gridColumn: "span 2", fontSize: 12 }}>
                      Uses a custom pattern ({field.strategy}) — switch to Advanced (JSON) to edit it.
                    </span>
                    <input
                      type="checkbox"
                      checked={field.required}
                      onChange={(e) => updateField(i, { required: e.target.checked })}
                      disabled={!canEdit}
                    />
                    <button type="button" className="danger icon-btn" onClick={() => removeField(i)} disabled={!canEdit}>
                      ×
                    </button>
                  </div>
                );
              })}
              {definition.fields.length === 0 && <p className="muted">No fields yet — add one below.</p>}
            </div>

            <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
              <button type="button" className="secondary small" onClick={addAnchorField} disabled={!canEdit}>
                + Add field
              </button>
              <button type="button" className="secondary small" onClick={addTableField} disabled={!canEdit}>
                + Add table field
              </button>
              <button type="button" className="secondary small" onClick={openJsonMode} style={{ marginLeft: "auto" }}>
                Advanced (JSON)
              </button>
            </div>
          </>
        ) : (
          <>
            <textarea
              rows={20}
              style={{ width: "100%" }}
              value={jsonDraft}
              onChange={(e) => setJsonDraft(e.target.value)}
              disabled={!canEdit}
            />
            <div style={{ marginTop: 10 }}>
              <button type="button" className="secondary small" onClick={applyJsonAndReturnToSimple}>
                ← Back to simple editor
              </button>
            </div>
          </>
        )}

        {!canEdit && (
          <p className="muted" style={{ marginTop: 12 }}>
            {canPreview
              ? "Your role can preview extraction below, but saving or activating rule set changes requires an admin."
              : "You have read-only access to rule sets."}
          </p>
        )}

        <div style={{ marginTop: 16 }}>
          <button onClick={handleSave} disabled={saving || !canEdit}>
            {saving ? "Saving..." : "Save as new version"}
          </button>{" "}
          {!isNew && !active && version && (
            <button className="secondary" onClick={handleActivate} disabled={!canEdit}>
              Activate this version
            </button>
          )}
        </div>
      </div>

      {canPreview && <div className="card">
        <h2 style={{ fontSize: 15, marginTop: 0 }}>Test against sample text</h2>
        <textarea
          rows={8}
          style={{ width: "100%" }}
          placeholder="Paste sample document text here..."
          value={sampleText}
          onChange={(e) => setSampleText(e.target.value)}
        />
        <div style={{ marginTop: 10 }}>
          <button className="secondary" onClick={handlePreview} disabled={!sampleText.trim()}>
            Preview extraction
          </button>
        </div>
        {previewResult && (
          <table style={{ marginTop: 12 }}>
            <thead>
              <tr>
                <th>Field</th>
                <th>Value</th>
                <th>Confidence</th>
                <th>Required</th>
              </tr>
            </thead>
            <tbody>
              {previewResult.map((f) => (
                <tr key={f.name}>
                  <td>{f.name}</td>
                  <td>{f.found ? JSON.stringify(f.value) : <span className="muted">not found</span>}</td>
                  <td>{(f.confidence * 100).toFixed(0)}%</td>
                  <td>{f.required ? "yes" : "no"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>}
    </div>
  );
}
