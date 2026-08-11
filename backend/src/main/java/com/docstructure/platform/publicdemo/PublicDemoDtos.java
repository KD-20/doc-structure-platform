package com.docstructure.platform.publicdemo;

import com.docstructure.platform.documents.Document;
import com.docstructure.platform.documents.DocumentStatus;
import com.docstructure.platform.extraction.ExtractionRun;
import com.docstructure.platform.extraction.ExtractionRunStatus;
import com.docstructure.platform.extraction.ExtractionStrategyType;
import com.docstructure.platform.search.ExtractedData;
import com.docstructure.platform.search.ExtractedDataStatus;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Deliberately its own small response shapes rather than reusing DocumentSummaryResponse/
 * ExtractionRunResponse/ExtractedDataResponse — those are package-private to their own
 * controllers, and duplicating a handful of fields here keeps the anonymous surface visibly
 * separate from (and easy to audit against) the authenticated API rather than quietly sharing
 * a type that might grow fields later that shouldn't be public.
 */
record PublicDocumentResponse(UUID id, String filename, String contentType, String docType, long fileSizeBytes,
                               DocumentStatus status, Instant createdAt, int uploadsUsed, int uploadsLimit) {
    static PublicDocumentResponse from(Document d, int uploadsUsed, int uploadsLimit) {
        return new PublicDocumentResponse(d.getId(), d.getFilename(), d.getContentType(), d.getDocType(),
                d.getFileSizeBytes(), d.getStatus(), d.getCreatedAt(), uploadsUsed, uploadsLimit);
    }
}

record PublicExtractionRunResponse(UUID id, ExtractionStrategyType strategy, ExtractionRunStatus status,
                                    Instant startedAt, Instant completedAt, String errorMessage) {
    static PublicExtractionRunResponse from(ExtractionRun r) {
        return new PublicExtractionRunResponse(r.getId(), r.getStrategy(), r.getStatus(), r.getStartedAt(),
                r.getCompletedAt(), r.getErrorMessage());
    }
}

record PublicExtractedDataResponse(UUID id, String docType, JsonNode fields, double overallConfidence,
                                    ExtractedDataStatus status, Instant createdAt) {
    static PublicExtractedDataResponse from(ExtractedData d) {
        return new PublicExtractedDataResponse(d.getId(), d.getDocType(), d.getFields(),
                d.getOverallConfidence() != null ? d.getOverallConfidence().doubleValue() : 0.0,
                d.getStatus(), d.getCreatedAt());
    }
}
