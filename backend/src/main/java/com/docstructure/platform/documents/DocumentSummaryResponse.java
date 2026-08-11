package com.docstructure.platform.documents;

import java.time.Instant;
import java.util.UUID;

/** Public (unlike the rest of DocumentDtos.java): PublicDemoService, in a different package, needs to read the id off DocumentService#upload's return value for anonymous trial uploads. */
public record DocumentSummaryResponse(UUID id, String filename, String contentType, String docType, long fileSizeBytes,
                                       DocumentStatus status, Instant createdAt) {
    static DocumentSummaryResponse from(Document d) {
        return new DocumentSummaryResponse(d.getId(), d.getFilename(), d.getContentType(), d.getDocType(),
                d.getFileSizeBytes(), d.getStatus(), d.getCreatedAt());
    }
}
