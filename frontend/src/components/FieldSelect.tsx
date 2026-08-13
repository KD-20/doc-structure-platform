import { useEffect, useRef, useState } from "react";
import { apiClient } from "../api/client";
import type { Page } from "../api/types";

const PAGE_SIZE = 5;

// Sentinel meaning "match if ANY extracted field satisfies the operator/value" — see
// SearchQueryBuilder.ANY_FIELD on the backend, which this string must match exactly.
export const ANY_FIELD = "*";

interface Props {
  tenantId: string;
  docType: string;
  value: string;
  onChange: (value: string) => void;
}

/**
 * Free-text field-name input with a live-filtered, paginated suggestion dropdown underneath,
 * fetching 5 real extracted field names at a time from /search/fields instead of loading every
 * field name across the tenant up front. Still a plain text input, not a forced <select> — an
 * unlisted/custom field name typed directly still works, the dropdown is just a memory aid.
 */
export function FieldSelect({ tenantId, docType, value, onChange }: Props) {
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<string[]>([]);
  const [page, setPage] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function closeOnOutsideClick(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", closeOnOutsideClick);
    return () => document.removeEventListener("mousedown", closeOnOutsideClick);
  }, []);

  function loadPage(targetPage: number, searchTerm: string) {
    setLoading(true);
    apiClient
      .get<Page<string>>(`/tenants/${tenantId}/search/fields`, {
        params: { docType: docType || undefined, q: searchTerm || undefined, page: targetPage, size: PAGE_SIZE },
      })
      .then((res) => {
        setItems((prev) => (targetPage === 0 ? res.data.content : [...prev, ...res.data.content]));
        setPage(targetPage);
        setTotalElements(res.data.totalElements);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }

  // Re-queries (debounced) on every keystroke while open — "inv" should surface
  // "invoice_number"/"invoice_date" instead of the same fixed first page regardless of what's
  // typed. Also re-queries on a docType change, since that changes which fields are relevant.
  // Skipped while closed so picking a suggestion (which also changes `value`) doesn't refetch
  // right after the panel closes.
  useEffect(() => {
    if (!open) return;
    const handle = setTimeout(() => loadPage(0, value), 200);
    return () => clearTimeout(handle);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, value, docType, tenantId]);

  function handleFocus() {
    setOpen(true);
  }

  const hasMore = items.length < totalElements;

  return (
    <div className="field-select" ref={containerRef}>
      <input
        placeholder="field"
        value={value === ANY_FIELD ? "Any field" : value}
        onChange={(e) => onChange(e.target.value)}
        onFocus={handleFocus}
      />
      {open && (
        <div className="field-select-panel">
          <div
            className="field-select-item"
            style={{ fontStyle: "italic" }}
            onClick={() => {
              onChange(ANY_FIELD);
              setOpen(false);
            }}
          >
            Any field — match this value on whichever field has it
          </div>
          {items.map((name) => (
            <div
              key={name}
              className="field-select-item"
              onClick={() => {
                onChange(name);
                setOpen(false);
              }}
            >
              {name}
            </div>
          ))}
          {items.length === 0 && !loading && (
            <p className="muted" style={{ padding: 8, margin: 0, fontSize: 12 }}>
              {value ? "No matching fields." : "No extracted fields yet."}
            </p>
          )}
          {hasMore && (
            <button
              type="button"
              className="secondary small"
              style={{ width: "100%", marginTop: 4 }}
              disabled={loading}
              onClick={() => loadPage(page + 1, value)}
            >
              {loading ? "Loading..." : "View more"}
            </button>
          )}
        </div>
      )}
    </div>
  );
}
