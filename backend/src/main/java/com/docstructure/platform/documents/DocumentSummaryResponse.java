package com.docstructure.platform.documents;

import com.docstructure.platform.extraction.ExtractionRunStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Public (unlike the rest of DocumentDtos.java): PublicDemoService, in a different package,
 * needs to read the id off DocumentService#upload's return value for anonymous trial uploads.
 * <p>
 * latestExtractionRunStatus is null when no extraction run has ever existed for this document
 * (never auto-triggered and never manually run); otherwise it's the most recent run's status
 * (PENDING/RUNNING/SUCCEEDED/FAILED) regardless of the document's own `status` field — the two
 * can legitimately disagree, e.g. `status: TEXT_EXTRACTED` with `latestExtractionRunStatus:
 * SUCCEEDED` means a run completed but found no matching rule set (see
 * RuleBasedExtractionStrategy's UNSTRUCTURED fallback), not "nothing has happened yet." This is
 * what lets the UI show a live "processing…" state or an honest "unstructured" state instead of
 * a misleading static one — see docs/DECISIONS.md.
 */
public record DocumentSummaryResponse(UUID id, String filename, String contentType, String docType, long fileSizeBytes,
                                       DocumentStatus status, Instant createdAt,
                                       ExtractionRunStatus latestExtractionRunStatus) {
    static DocumentSummaryResponse from(Document d, ExtractionRunStatus latestExtractionRunStatus) {
        return new DocumentSummaryResponse(d.getId(), d.getFilename(), d.getContentType(), d.getDocType(),
                d.getFileSizeBytes(), d.getStatus(), d.getCreatedAt(), latestExtractionRunStatus);
    }
}
