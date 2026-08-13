package com.docstructure.platform.extraction;

import com.docstructure.platform.audit.AuditService;
import com.docstructure.platform.common.TenantScoped;
import com.docstructure.platform.documents.DocumentEventService;
import com.docstructure.platform.documents.DocumentRepository;
import com.docstructure.platform.documents.DocumentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A genuinely separate bean (not just a private method on ExtractionService) for one reason:
 * REQUIRES_NEW. If the strategy/audit work inside ExtractionService#performExtraction's own
 * transaction fails because the underlying run/document row was deleted concurrently (e.g. a
 * document deleted mid-extraction — a real window now that this is async, not just a test
 * artifact), any nested @Transactional call that touches that stale state (AuditService#record,
 * itself @TenantScoped/@Transactional) marks the WHOLE ambient transaction rollback-only the
 * moment it throws — catching that exception in Java doesn't undo Spring's rollback-only flag,
 * so performExtraction's own commit at method-end would fail with UnexpectedRollbackException
 * even though the exception was "handled." Calling this method (REQUIRES_NEW suspends the
 * poisoned transaction and starts a fresh one) is what actually lets failure-recording succeed
 * or fail independently — and self-invocation from within ExtractionService itself wouldn't get
 * a proxy at all, so this has to live on its own bean either way.
 */
@Component
public class ExtractionFailureRecorder {

    private static final Logger log = LoggerFactory.getLogger(ExtractionFailureRecorder.class);

    private final ExtractionRunRepository runRepository;
    private final DocumentRepository documentRepository;
    private final AuditService auditService;
    private final DocumentEventService documentEventService;

    public ExtractionFailureRecorder(ExtractionRunRepository runRepository, DocumentRepository documentRepository,
                                      AuditService auditService, DocumentEventService documentEventService) {
        this.runRepository = runRepository;
        this.documentRepository = documentRepository;
        this.auditService = auditService;
        this.documentEventService = documentEventService;
    }

    @TenantScoped
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID tenantId, UUID documentId, UUID runId, String errorMessage) {
        try {
            runRepository.findById(runId).ifPresent(run -> {
                run.setStatus(ExtractionRunStatus.FAILED);
                run.setCompletedAt(Instant.now());
                run.setErrorMessage(errorMessage);
                runRepository.save(run);
            });
            documentRepository.findById(documentId).ifPresent(doc -> {
                doc.setStatus(DocumentStatus.STRUCTURING_FAILED);
                documentRepository.save(doc);
                documentEventService.publishStatusChange(tenantId, documentId, doc.getStatus(), doc.getDocType());
            });
            documentEventService.publishExtractionStatus(tenantId, documentId, ExtractionRunStatus.FAILED);
            auditService.record("EXTRACTION_RUN_FAILED", "EXTRACTION_RUN", runId,
                    Map.of("documentId", documentId, "status", "FAILED", "error", String.valueOf(errorMessage)));
        } catch (RuntimeException e) {
            // The document/run was deleted out from under us — nothing left to mark FAILED or
            // notify. Log and stop rather than letting a second failure here escape as an
            // unhandled worker error; this transaction's own rollback affects only this attempt.
            log.warn("could not record extraction failure for run={} (document/run likely deleted concurrently): {}",
                    runId, e.toString());
        }
    }
}
