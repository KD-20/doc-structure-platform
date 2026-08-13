import type { DocumentStatus, ExtractionRunStatus } from "../api/types";

// A minimal structural shape (rather than the full ExtractionRun) so both the authenticated
// ExtractionRun and the anonymous-trial PublicExtractionRun satisfy it without an adapter.
interface MinimalRun {
  status: ExtractionRunStatus;
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
  skipped: "⏭",
};

interface Step {
  label: string;
  state: StepState;
  note?: string;
}

/**
 * Text extraction still runs inline within the upload request, but structured extraction is
 * async (see ExtractionService#enqueueExtraction) — a document can sit at TEXT_EXTRACTED for a
 * little while with a run genuinely PENDING/RUNNING in the background. Document status alone
 * can't distinguish "not yet run" from "running right now" (DocumentStatus doesn't have an
 * in-progress value), so this leans on latestRun's own status for that distinction whenever the
 * document hasn't reached a terminal STRUCTURED/STRUCTURING_FAILED state yet.
 */
function buildSteps(status: DocumentStatus, latestRun?: MinimalRun): Step[] {
  const textState: StepState = status === "TEXT_EXTRACTION_FAILED" ? "failed" : "done";

  let structureState: StepState;
  let structureNote: string | undefined;
  let unstructuredStep: Step | undefined;
  if (status === "STRUCTURED") {
    structureState = "done";
  } else if (status === "STRUCTURING_FAILED") {
    structureState = "failed";
    structureNote = latestRun?.errorMessage ?? "Extraction failed";
  } else if (status === "TEXT_EXTRACTION_FAILED") {
    structureState = "skipped";
    structureNote = "No text to structure";
  } else if (latestRun?.status === "RUNNING") {
    structureState = "pending";
    structureNote = "Running now…";
  } else if (latestRun?.status === "PENDING") {
    structureState = "pending";
    structureNote = "Queued…";
  } else if (latestRun?.status === "SUCCEEDED") {
    // A run genuinely completed, but document status stayed TEXT_EXTRACTED (not STRUCTURED) —
    // that combination only happens for RuleBasedExtractionStrategy's UNSTRUCTURED fallback (no
    // matching rule set, see its own javadoc). It already ran automatically; "Not yet run" here
    // would wrongly suggest a manual retrigger is still needed. Surfaced as its own completed
    // step (ticked) rather than folded into the skipped "Structured" note, so the journey reads
    // as "skipped structuring, but still finished successfully" instead of just stopping short.
    structureState = "skipped";
    structureNote = "No matching rule set — nothing to structure";
    unstructuredStep = { label: "Unstructured", state: "done", note: "Indexed for search" };
  } else {
    structureState = "pending";
    structureNote = "Not yet run";
  }

  const steps: Step[] = [
    { label: "Uploaded", state: "done" },
    {
      label: "Text extracted",
      state: textState,
      note: textState === "failed" ? "Tika couldn't read this file" : undefined,
    },
    { label: "Structured", state: structureState, note: structureNote },
  ];
  if (unstructuredStep) steps.push(unstructuredStep);
  return steps;
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
