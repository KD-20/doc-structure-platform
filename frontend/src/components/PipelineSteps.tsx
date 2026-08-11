import type { DocumentStatus } from "../api/types";

// Only errorMessage is actually read below — a minimal structural shape (rather than the full
// ExtractionRun) so both the authenticated ExtractionRun and the anonymous-trial
// PublicExtractionRun satisfy it without an adapter.
interface MinimalRun {
  errorMessage: string | null;
}

type StepState = "done" | "failed" | "pending" | "skipped";

const DOT_CLASS: Record<StepState, string> = {
  done: "status-ok",
  failed: "status-error",
  pending: "status-neutral",
  skipped: "status-neutral",
};

const SYMBOL: Record<StepState, string> = {
  done: "✓",
  failed: "✗",
  pending: "○",
  skipped: "–",
};

interface Step {
  label: string;
  state: StepState;
  note?: string;
}

/**
 * Processing here is synchronous (see docs/DECISIONS.md) — text extraction and structuring
 * both run inline within the upload/trigger request, not as a background job. So there's no
 * "time remaining" to show: by the time a document's status is visible at all, whatever's
 * going to happen to it already has. This shows *what* happened/is pending instead of
 * fabricating a progress percentage for a queue that doesn't exist.
 */
function buildSteps(status: DocumentStatus, latestRun?: MinimalRun): Step[] {
  const textState: StepState = status === "TEXT_EXTRACTION_FAILED" ? "failed" : "done";

  let structureState: StepState;
  let structureNote: string | undefined;
  if (status === "STRUCTURED") {
    structureState = "done";
  } else if (status === "STRUCTURING_FAILED") {
    structureState = "failed";
    structureNote = latestRun?.errorMessage ?? "Extraction failed";
  } else if (status === "TEXT_EXTRACTION_FAILED") {
    structureState = "skipped";
    structureNote = "No text to structure";
  } else {
    structureState = "pending";
    structureNote = "Not yet run";
  }

  return [
    { label: "Uploaded", state: "done" },
    {
      label: "Text extracted",
      state: textState,
      note: textState === "failed" ? "Tika couldn't read this file" : undefined,
    },
    { label: "Structured", state: structureState, note: structureNote },
  ];
}

export function PipelineSteps({ status, latestRun }: { status: DocumentStatus; latestRun?: MinimalRun }) {
  return (
    <div className="pipeline-steps">
      {buildSteps(status, latestRun).map((step) => (
        <div className="pipeline-step" key={step.label}>
          <span className={`pipeline-step-dot ${DOT_CLASS[step.state]}`}>{SYMBOL[step.state]}</span>
          <span>
            {step.label}
            {step.note && <span className="muted"> — {step.note}</span>}
          </span>
        </div>
      ))}
    </div>
  );
}
