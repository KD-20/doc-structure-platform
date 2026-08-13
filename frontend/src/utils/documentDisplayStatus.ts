import type { DocumentSummary } from "../api/types";

/**
 * The raw DocumentStatus alone can't distinguish "hasn't been processed yet" from "a job is
 * running right now" from "it ran and genuinely found nothing to structure" — all three show as
 * TEXT_EXTRACTED. latestExtractionRunStatus disambiguates: PENDING/RUNNING means a background
 * job is actually in flight (show a live loader instead of a static, misleading pill);
 * TEXT_EXTRACTED + a SUCCEEDED run means unstructured-but-searchable, not "not yet run". See
 * DocumentSummaryResponse's own javadoc on the backend for the full reasoning.
 *
 * PROCESSING/UNSTRUCTURED are synthetic — not real DocumentStatus values, just what StatusPill
 * is told to render; see its own CLASS_BY_STATUS/DESCRIPTION_BY_STATUS entries for them.
 */
export function displayStatus(d: DocumentSummary): string {
  if (d.latestExtractionRunStatus === "PENDING" || d.latestExtractionRunStatus === "RUNNING") {
    return "PROCESSING";
  }
  if (d.status === "TEXT_EXTRACTED" && d.latestExtractionRunStatus === "SUCCEEDED") {
    return "UNSTRUCTURED";
  }
  return d.status;
}
