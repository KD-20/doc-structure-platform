package com.docstructure.platform.extraction;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** Response records for the extraction API. Package-private: only ExtractionController needs these. */
record ExtractionRunResponse(UUID id, UUID documentId, ExtractionStrategyType strategy, ExtractionRunStatus status,
                              Instant startedAt, Instant completedAt, String errorMessage) {
    static ExtractionRunResponse from(ExtractionRun run) {
        return new ExtractionRunResponse(run.getId(), run.getDocumentId(), run.getStrategy(), run.getStatus(),
                run.getStartedAt(), run.getCompletedAt(), run.getErrorMessage());
    }
}

record ExtractedDataResponse(UUID id, UUID documentId, String docType, JsonNode fields, double overallConfidence,
                              String status, Instant createdAt) {
}
