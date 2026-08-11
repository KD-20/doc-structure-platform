package com.docstructure.platform.documents;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/** Request/response records for the documents API. Package-private: only DocumentController needs these. (DocumentSummaryResponse now lives in its own file — it's public, see DocumentSummaryResponse.java.) */
record RawTextResponse(UUID documentId, String rawText) {
}

record UpdateDocTypeRequest(@NotBlank String docType) {
}
