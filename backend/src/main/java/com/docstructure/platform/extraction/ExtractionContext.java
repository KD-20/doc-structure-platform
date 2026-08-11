package com.docstructure.platform.extraction;

import java.util.UUID;

public record ExtractionContext(UUID tenantId, UUID documentId, String docType, String rawText) {
}
