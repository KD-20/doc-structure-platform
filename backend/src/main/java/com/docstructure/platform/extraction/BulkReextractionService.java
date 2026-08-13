package com.docstructure.platform.extraction;

import com.docstructure.platform.common.TenantScoped;
import com.docstructure.platform.documents.Document;
import com.docstructure.platform.documents.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Re-syncs already-uploaded documents with a rule set change. Without this, updating (or
 * activating an older version of) a rule set only affects documents uploaded/extracted from
 * that point on — every existing document of that doc type silently keeps whatever its last run
 * produced until someone manually re-triggers each one individually. Called two ways: manually
 * (RuleSetController's own reextract endpoint) and automatically, right after a rule set version
 * is created or activated (see RuleSetController).
 *
 * A separate bean from ExtractionService rather than a method on it: enqueueExtraction is called
 * per document in a loop here, and that call needs to cross a real Spring proxy boundary for its
 * own @TenantScoped/@Transactional to actually apply (see ExtractionWorker's javadoc on the
 * self-invocation gotcha) — trivially true when the caller is a different bean.
 */
@Service
public class BulkReextractionService {

    private static final Logger log = LoggerFactory.getLogger(BulkReextractionService.class);

    private final DocumentRepository documentRepository;
    private final ExtractionService extractionService;

    public BulkReextractionService(DocumentRepository documentRepository, ExtractionService extractionService) {
        this.documentRepository = documentRepository;
        this.extractionService = extractionService;
    }

    public record Result(int documentsEnqueued, int documentsSkipped) {
    }

    /**
     * Skips documents with no extracted text yet (nothing to structure — same guard
     * enqueueExtraction itself applies for a single document) rather than failing the whole
     * batch on one document that isn't ready. Each enqueue is its own small transaction (see
     * class javadoc), so one document failing to enqueue doesn't roll back the others — but
     * enqueueExtraction itself doesn't currently throw for anything this loop would hit besides
     * the not-ready case already filtered out below.
     */
    @TenantScoped
    @Transactional
    public Result reextractByDocType(UUID tenantId, String docType, UUID triggeredByUserId) {
        List<Document> documents = documentRepository.findByTenantIdAndDocType(tenantId, docType);
        int enqueued = 0;
        int skipped = 0;
        for (Document document : documents) {
            if (document.getRawText() == null || document.getRawText().isBlank()) {
                skipped++;
                continue;
            }
            extractionService.enqueueExtraction(tenantId, document.getId(), triggeredByUserId);
            enqueued++;
        }
        log.info("bulk re-extraction tenant={} docType={} enqueued={} skipped={} triggeredBy={}",
                tenantId, docType, enqueued, skipped, triggeredByUserId);
        return new Result(enqueued, skipped);
    }
}
