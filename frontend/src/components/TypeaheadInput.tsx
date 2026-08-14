import { useEffect, useRef, useState, type CSSProperties } from "react";

/**
 * Free-text input with a suggestion dropdown — a custom replacement for
 * `<input list="..."><datalist>`, which turned out to be unreliable in practice: once the
 * value matches a suggestion exactly, browsers often only show that single option on
 * reopen, making it look like there's no way back to the full list or to a brand-new value.
 * This owns its own open/close state and filtering instead of leaving that to browser-native
 * (and inconsistent) datalist behavior.
 */
export function TypeaheadInput({
  value,
  onChange,
  suggestions,
  placeholder,
  inputStyle,
  autoFocus,
}: {
  value: string;
  onChange: (value: string) => void;
  suggestions: string[];
  placeholder?: string;
  inputStyle?: CSSProperties;
  autoFocus?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const wrapperRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function closeOnOutsideClick(e: MouseEvent) {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", closeOnOutsideClick);
    return () => document.removeEventListener("mousedown", closeOnOutsideClick);
  }, []);

  const filtered = value.trim()
    ? suggestions.filter((s) => s.toLowerCase().includes(value.trim().toLowerCase()))
    : suggestions;

  return (
    <div className="typeahead" ref={wrapperRef}>
      <div className="select-wrapper">
        <input
          value={value}
          placeholder={placeholder}
          style={inputStyle}
          autoFocus={autoFocus}
          onChange={(e) => {
            onChange(e.target.value);
            setOpen(true);
          }}
          onFocus={() => setOpen(true)}
        />
        {value && (
          <button
            type="button"
            className="select-wrapper-clear"
            title="Clear"
            onClick={() => {
              onChange("");
              setOpen(true);
            }}
          >
            ×
          </button>
        )}
        <button
          type="button"
          className="select-wrapper-chevron"
          title="Show suggestions"
          tabIndex={-1}
          onMouseDown={(e) => e.preventDefault()}
          onClick={() => setOpen((o) => !o)}
        >
          {open ? "▲" : "▾"}
        </button>
      </div>
      {open && filtered.length > 0 && (
        <div className="typeahead-menu">
          {filtered.map((s) => (
            <div
              key={s}
              className="typeahead-option"
              // mousedown, not click: fires before the input blurs, so picking a suggestion
              // isn't preempted by losing focus first.
              onMouseDown={(e) => {
                e.preventDefault();
                onChange(s);
                setOpen(false);
              }}
            >
              {s}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
