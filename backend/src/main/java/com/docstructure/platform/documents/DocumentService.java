package com.docstructure.platform.documents;

import com.docstructure.platform.audit.Audited;
import com.docstructure.platform.common.ApiExceptions;
import com.docstructure.platform.common.TenantScoped;
import com.docstructure.platform.extraction.DocTypeClassifier;
import com.docstructure.platform.extraction.ExtractionRunRepository;
import com.docstructure.platform.extraction.ExtractionRunStatus;
import com.docstructure.platform.extraction.ExtractionService;
import com.docstructure.platform.extraction.ExtractionStrategyFactory;
import com.docstructure.platform.extraction.LlmExtractionStrategy;
import com.docstructure.platform.rules.RuleSetService;
import com.docstructure.platform.storage.StorageService;
import com.docstructure.platform.tenancy.Tenant;
import com.docstructure.platform.tenancy.TenantRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final TikaTextExtractor textExtractor;
    private final DocTypeClassifier docTypeClassifier;
    private final ExtractionService extractionService;
    private final ExtractionRunRepository extractionRunRepository;
    private final ExtractionStrategyFactory extractionStrategyFactory;
    private final TenantRepository tenantRepository;
    private final DocumentEventService documentEventService;
    private final MeterRegistry meterRegistry;
    private final RuleSetService ruleSetService;

    public DocumentService(DocumentRepository documentRepository, StorageService storageService,
                            TikaTextExtractor textExtractor, DocTypeClassifier docTypeClassifier,
                            ExtractionService extractionService, ExtractionRunRepository extractionRunRepository,
                            ExtractionStrategyFactory extractionStrategyFactory,
                            TenantRepository tenantRepository, DocumentEventService documentEventService,
                            MeterRegistry meterRegistry, RuleSetService ruleSetService) {
        this.documentRepository = documentRepository;
        this.storageService = storageService;
        this.textExtractor = textExtractor;
        this.docTypeClassifier = docTypeClassifier;
        this.extractionService = extractionService;
        this.extractionRunRepository = extractionRunRepository;
        this.extractionStrategyFactory = extractionStrategyFactory;
        this.tenantRepository = tenantRepository;
        this.documentEventService = documentEventService;
        this.meterRegistry = meterRegistry;
        this.ruleSetService = ruleSetService;
    }

    /**
     * Stores the file and runs Tika text extraction inline. docType is optional: an uploader
     * shouldn't need to know anything about rule sets to use this system, so when it's omitted,
     * DocTypeClassifier tries, in order: (1) a known document-type word in the filename itself
     * (e.g. "March_Invoice_2024.pdf" -> "invoice"); (2) a generic extension/content-type bucket
     * ("pdf_document", "image", ...) as the absolute last resort. An explicitly-provided docType
     * always wins over classification (e.g. a user overriding a wrong guess).
     * <p>
     * Deliberately NOT scoring the document's own text against candidate rule sets to guess a
     * business type (an earlier tier here did exactly that) — a handful of loosely-defined
     * anchor-regex fields can score "confident" against text that has nothing to do with that
     * doc type (observed live: a "Commands" text file scored high enough against the shipped
     * resume rule set to get labeled "resume" and marked STRUCTURED). No doc type selected now
     * always means exactly that — type not known — rather than a guess dressed up as a fact.
     * <p>
     * Extraction (async, see ExtractionService#enqueueExtraction) auto-triggers whenever docType
     * was left for classification, so a with-no-doc-type upload still ends up embedded for
     * semantic/fuzzy search via RuleBasedExtractionStrategy's UNSTRUCTURED fallback — it just
     * can't be miscategorized as a specific business type it was never told and can't safely
     * infer. A tenant configured for LLM extraction (which never needs a rule set for any doc
     * type) always auto-triggers too, explicit docType or not. An explicitly-typed upload also
     * auto-triggers when a rule set (tenant-custom or platform default) actually resolves for
     * that docType — the uploader named a real, working type, there's no reason to make them
     * click "run" separately (confirmed live as a real gap: uploading with docType "resume",
     * which already has a working custom rule set, silently sat at "Structured — Not yet run"
     * until manually triggered). Only genuinely skipped when the explicit docType has no rule
     * set at all — nothing would happen anyway, so leaving it for a manual retrigger (or a
     * doc-type change, which already always retriggers) once a rule set exists is more useful
     * than silently embedding it now.
     */
    @TenantScoped
    @Transactional
    @Audited(action = "DOCUMENT_UPLOADED", entityType = "DOCUMENT")
    public DocumentSummaryResponse upload(UUID tenantId, MultipartFile file, String docTypeParam, UUID uploaderId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return doUpload(tenantId, file, docTypeParam, uploaderId);
        } finally {
            sample.stop(meterRegistry.timer("document.upload.duration"));
        }
    }

    private DocumentSummaryResponse doUpload(UUID tenantId, MultipartFile file, String docTypeParam, UUID uploaderId) {
        if (file.isEmpty()) {
            throw new ApiExceptions.ValidationException("File is empty");
        }
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unnamed";

        // storageKey namespaces the file path only; it's independent of whatever id Hibernate
        // assigns the Document row below, so file I/O can happen before any DB write. Persisting
        // a half-populated entity and mutating it afterward risks Hibernate flushing the INSERT
        // before the later fields are set (observed in practice: storage_path NOT NULL violation) —
        // building the complete entity first and saving once avoids that entirely.
        UUID storageKey = UUID.randomUUID();
        String storagePath;
        try (InputStream in = file.getInputStream()) {
            storagePath = storageService.store(tenantId, storageKey, filename, in);
        } catch (IOException e) {
            throw new ApiExceptions.ValidationException("Failed to store file: " + e.getMessage());
        }

        String rawText = null;
        DocumentStatus status;
        try (InputStream in = storageService.retrieve(storagePath)) {
            rawText = textExtractor.extract(in);
            status = DocumentStatus.TEXT_EXTRACTED;
        } catch (Exception e) {
            log.warn("Text extraction failed for storage path {}: {}", storagePath, e.toString());
            status = DocumentStatus.TEXT_EXTRACTION_FAILED;
        }

        boolean wasClassified = docTypeParam == null || docTypeParam.isBlank();
        String docType;
        if (!wasClassified) {
            docType = docTypeParam.trim();
        } else {
            docType = docTypeClassifier.classifyByFilename(filename)
                    .orElseGet(() -> docTypeClassifier.deriveFallbackDocType(filename, file.getContentType()));
        }

        Document doc = new Document();
        doc.setTenantId(tenantId);
        doc.setFilename(filename);
        doc.setContentType(file.getContentType());
        doc.setDocType(docType);
        doc.setFileSizeBytes(file.getSize());
        doc.setUploadedByUserId(uploaderId);
        doc.setStoragePath(storagePath);
        doc.setRawText(rawText);
        doc.setStatus(status);
        // saveAndFlush, not save: @CreationTimestamp/@UpdateTimestamp are only populated on
        // the in-memory entity once the INSERT actually runs, which plain save() may defer
        // past this point — the immediate response would otherwise show createdAt: null.
        doc = documentRepository.saveAndFlush(doc);

        // See the method javadoc for exactly which case this covers and which it doesn't.
        boolean shouldAutoTrigger = status == DocumentStatus.TEXT_EXTRACTED
                && (wasClassified || usesLlmExtraction(tenantId) || hasResolvableRuleSet(tenantId, docType));
        if (shouldAutoTrigger) {
            try {
                // Async now (see ExtractionService#enqueueExtraction) — doc stays at
                // TEXT_EXTRACTED in this response; the eventual STRUCTURED/STRUCTURING_FAILED
                // transition arrives via the SSE stream this method already publishes to below.
                extractionService.enqueueExtraction(tenantId, doc.getId(), uploaderId);
            } catch (RuntimeException e) {
                // Upload succeeding matters more than the auto-extraction convenience step;
                // the document just stays at TEXT_EXTRACTED and can still be extracted manually.
                log.warn("auto-extraction enqueue failed for document={}: {}", doc.getId(), e.toString());
            }
        }

        log.info("document uploaded id={} tenant={} filename={} docType={} status={} sizeBytes={}",
                doc.getId(), tenantId, filename, docType, status, doc.getFileSizeBytes());
        meterRegistry.counter("documents.uploaded", "status", status.name()).increment();
        documentEventService.publishStatusChange(tenantId, doc.getId(), doc.getStatus(), doc.getDocType());
        return DocumentSummaryResponse.from(doc, latestRunStatus(tenantId, doc.getId()));
    }

    private boolean usesLlmExtraction(UUID tenantId) {
        try {
            Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
            return tenant != null && extractionStrategyFactory.resolve(tenant.getSettings()) instanceof LlmExtractionStrategy;
        } catch (RuntimeException e) {
            // Misconfigured strategy (e.g. tenant set to LLM but platform.extraction.llm.enabled
            // is false) — treat as "not LLM" here; a real extraction attempt surfaces the
            // actual error rather than this best-effort upload-time check.
            return false;
        }
    }

    /**
     * Same resolution RuleBasedExtractionStrategy itself would use at extraction time (tenant's
     * own active rule set for this doc type, falling back to a platform default) — an explicit
     * docType that resolves to a real rule set here is worth auto-triggering for, same as the
     * classification path already does unconditionally.
     */
    private boolean hasResolvableRuleSet(UUID tenantId, String docType) {
        return ruleSetService.resolveDefinition(tenantId, docType).isPresent();
    }

    /** Null when no extraction run has ever existed for this document — see DocumentSummaryResponse's own javadoc. */
    private ExtractionRunStatus latestRunStatus(UUID tenantId, UUID documentId) {
        return extractionRunRepository.findFirstByTenantIdAndDocumentIdOrderByCreatedAtDesc(tenantId, documentId)
                .map(run -> run.getStatus())
                .orElse(null);
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public Page<DocumentSummaryResponse> list(UUID tenantId, String docType, Pageable pageable) {
        Page<Document> page = docType != null
                ? documentRepository.findByTenantIdAndDocTypeOrderByCreatedAtDesc(tenantId, docType, pageable)
                : documentRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
        List<UUID> ids = page.getContent().stream().map(Document::getId).toList();
        // Batched (one query for the whole page), not per-row — see
        // ExtractionRunRepository#findLatestStatusByDocumentIds.
        Map<UUID, ExtractionRunStatus> latestByDoc = ids.isEmpty() ? Map.of()
                : extractionRunRepository.findLatestStatusByDocumentIds(tenantId, ids).stream()
                        .collect(Collectors.toMap(ExtractionRunRepository.LatestRunStatus::getDocumentId,
                                r -> ExtractionRunStatus.valueOf(r.getStatus())));
        return page.map(d -> DocumentSummaryResponse.from(d, latestByDoc.get(d.getId())));
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public DocumentSummaryResponse get(UUID tenantId, UUID documentId) {
        Document doc = requireDocument(documentId);
        return DocumentSummaryResponse.from(doc, latestRunStatus(tenantId, doc.getId()));
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public RawTextResponse getRawText(UUID tenantId, UUID documentId) {
        Document doc = requireDocument(documentId);
        return new RawTextResponse(doc.getId(), doc.getRawText());
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public DownloadHandle download(UUID tenantId, UUID documentId) throws IOException {
        Document doc = requireDocument(documentId);
        return new DownloadHandle(doc.getFilename(), doc.getContentType(), storageService.retrieve(doc.getStoragePath()));
    }

    /**
     * Fixes a document stuck on a generic auto-classified label (e.g. "pdf_document", "image")
     * that has no matching rule set — the uploader never has to know this exists, but an
     * editor can correct or override it here. Immediately retries extraction if there's raw
     * text to work with, same as upload's auto-extraction, so this is a single action ("set
     * the type") rather than "set the type, then remember to also click Run extraction."
     */
    @TenantScoped
    @Transactional
    @Audited(action = "DOCUMENT_TYPE_CHANGED", entityType = "DOCUMENT")
    public DocumentSummaryResponse updateDocType(UUID tenantId, UUID documentId, String newDocType, UUID actingUserId) {
        if (newDocType == null || newDocType.isBlank()) {
            throw new ApiExceptions.ValidationException("Document type must not be blank");
        }
        Document doc = requireDocument(documentId);
        String previousDocType = doc.getDocType();
        doc.setDocType(newDocType.trim());
        doc = documentRepository.save(doc);
        log.info("document docType changed id={} tenant={} from={} to={}", documentId, tenantId, previousDocType,
                doc.getDocType());

        if (doc.getRawText() != null && !doc.getRawText().isBlank()) {
            try {
                extractionService.enqueueExtraction(tenantId, doc.getId(), actingUserId);
            } catch (RuntimeException e) {
                log.warn("extraction enqueue after doc type change failed for document={}: {}", doc.getId(),
                        e.toString());
            }
        }
        documentEventService.publishStatusChange(tenantId, doc.getId(), doc.getStatus(), doc.getDocType());
        return DocumentSummaryResponse.from(doc, latestRunStatus(tenantId, doc.getId()));
    }

    @TenantScoped
    @Transactional
    public void delete(UUID tenantId, UUID documentId) {
        Document doc = requireDocument(documentId);
        try {
            storageService.delete(doc.getStoragePath());
        } catch (IOException e) {
            log.warn("Failed to delete stored file for document {}: {}", documentId, e.toString());
        }
        documentRepository.delete(doc);
    }

    private Document requireDocument(UUID documentId) {
        // RLS already scopes this lookup to the current tenant; a wrong-tenant id is
        // indistinguishable from a nonexistent one, which is exactly the intended behavior.
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Document not found"));
    }

    public record DownloadHandle(String filename, String contentType, InputStream content) {
    }
}
