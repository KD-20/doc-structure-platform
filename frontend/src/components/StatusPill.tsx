const CLASS_BY_STATUS: Record<string, string> = {
  UPLOADED: "status-neutral",
  TEXT_EXTRACTED: "status-neutral",
  TEXT_EXTRACTION_FAILED: "status-error",
  STRUCTURED: "status-ok",
  STRUCTURING_FAILED: "status-error",
  PENDING: "status-neutral",
  RUNNING: "status-warn",
  SUCCEEDED: "status-ok",
  FAILED: "status-error",
  COMPLETE: "status-ok",
  PARTIAL: "status-warn",
  NEEDS_REVIEW: "status-warn",
  // Synthetic, not real backend enum values — computed client-side (see DocumentsPage) from
  // status + latestExtractionRunStatus together, since neither field alone can tell "a
  // background job is in flight right now" or "it ran and genuinely found nothing to
  // structure" apart from "hasn't been touched yet" (both otherwise show as TEXT_EXTRACTED).
  PROCESSING: "status-warn",
  UNSTRUCTURED: "status-neutral",
};

// Hover text explaining what each status actually means — defined once here so every screen
// that renders a StatusPill (Documents, Detail, Search, Audit Log, ...) gets the same tooltip.
const DESCRIPTION_BY_STATUS: Record<string, string> = {
  UPLOADED: "File received, not yet processed",
  TEXT_EXTRACTED: "Text extracted; not yet structured",
  TEXT_EXTRACTION_FAILED: "Couldn't extract text from this file",
  STRUCTURED: "Structured extraction finished successfully",
  STRUCTURING_FAILED: "Structured extraction failed",
  PENDING: "Waiting to run",
  RUNNING: "Currently running",
  SUCCEEDED: "Completed successfully",
  FAILED: "Failed",
  COMPLETE: "All fields found",
  PARTIAL: "Some fields missing",
  NEEDS_REVIEW: "A required field is missing — needs review",
  PROCESSING: "Structured extraction is running in the background right now",
  UNSTRUCTURED: "No matching rule set — nothing to structure, but indexed for search",
};

export function StatusPill({ status }: { status: string }) {
  return (
    <span
      className={`status-pill ${CLASS_BY_STATUS[status] ?? "status-neutral"}`}
      title={DESCRIPTION_BY_STATUS[status] ?? status}
    >
      {status === "PROCESSING" && <span className="status-pill-spinner" />}
      {status}
    </span>
  );
}
