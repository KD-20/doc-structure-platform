import type { ExtractedFieldValue } from "../api/types";

function renderValue(value: unknown): string {
  if (value === null || value === undefined) return "(not found)";
  if (Array.isArray(value)) return JSON.stringify(value, null, 2);
  return String(value);
}

export function FieldsTable({ fields }: { fields: Record<string, ExtractedFieldValue> }) {
  return (
    <table>
      <thead>
        <tr>
          <th>Field</th>
          <th>Value</th>
          <th>Confidence</th>
        </tr>
      </thead>
      <tbody>
        {Object.entries(fields).map(([name, fv]) => (
          <tr key={name}>
            <td>{name}</td>
            <td>
              {Array.isArray(fv.value) ? (
                <pre className="json-view" style={{ margin: 0 }}>
                  {renderValue(fv.value)}
                </pre>
              ) : (
                renderValue(fv.value)
              )}
            </td>
            <td className="muted">{(fv.confidence * 100).toFixed(0)}%</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
