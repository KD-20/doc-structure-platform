package com.docstructure.platform.extraction;

import java.util.UUID;

/**
 * Published by ExtractionService.enqueueExtraction inside the enqueueing transaction;
 * ExtractionWorker only picks it up AFTER_COMMIT (see its @TransactionalEventListener), so the
 * background thread never races the PENDING run row's own INSERT.
 */
public record ExtractionRequestedEvent(UUID tenantId, UUID documentId, UUID runId, UUID triggeredByUserId) {
}
