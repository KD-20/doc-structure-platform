package com.docstructure.platform.documents;

import com.docstructure.platform.audit.Audited;
import com.docstructure.platform.common.ApiExceptions;
import com.docstructure.platform.common.TenantScoped;
import com.docstructure.platform.extraction.DocTypeClassifier;
import com.docstructure.platform.extraction.ExtractionService;
import com.docstructure.platform.extraction.ExtractionStrategyFactory;
import com.docstructure.platform.extraction.LlmExtractionStrategy;
import com.docstructure.platform.storage.StorageService;
import com.docstructure.platform.tenancy.Tenant;
import com.docstructure.platform.tenancy.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final TikaTextExtractor textExtractor;
    private final DocTypeClassifier docTypeClassifier;
    private final ExtractionService extractionService;
    private final ExtractionStrategyFactory extractionStrategyFactory;
    private final TenantRepository tenantRepository;
    private final DocumentEventService documentEventService;

    public DocumentService(DocumentRepository documentRepository, StorageService storageService,
                            TikaTextExtractor textExtractor, DocTypeClassifier docTypeClassifier,
                            ExtractionService extractionService, ExtractionStrategyFactory extractionStrategyFactory,
                            TenantRepository tenantRepository, DocumentEventService documentEventService) {
        this.documentRepository = documentRepository;
        this.storageService = storageService;
        this.textExtractor = textExtractor;
        this.docTypeClassifier = docTypeClassifier;
        this.extractionService = extractionService;
        this.extractionStrategyFactory = extractionStrategyFactory;
        this.tenantRepository = tenantRepository;
        this.documentEventService = documentEventService;
    }

    /**
     * Stores the file and runs Tika text extraction inline (v1 is synchronous — see
     * docs/DECISIONS.md). docType is optional: an uploader shouldn't need to know anything
     * about rule sets to use this system, so when it's omitted, DocTypeClassifier tries, in
     * order: (1) score the extracted text against every active rule set — if confident,
     * structured extraction also runs immediately, no separate click needed; (2) a known
     * document-type word in the filename itself; (3) a slug of the filename, or a generic
     * content-type bucket as the absolute last resort. Only tier (1) is trusted enough to
     * auto-run extraction for a RULE_BASED tenant — the others are about giving a meaningful
     * category instead of dumping everything unmatched into one flat "unclassified" bucket,
     * not a prediction that a rule set exists for it yet. An explicitly-provided docType
     * always wins over classification (e.g. a user overriding a wrong guess).
     * <p>
     * A tenant configured for LLM extraction (tenant.settings.extractionStrategy="LLM", see
     * LlmExtractionStrategy) always auto-triggers instead — it never needs a rule set for any
     * doc type, so there's no "unreliable guess" concern gating it the way there is for tier
     * 2/3/4 rule-based classification.
     */
    @TenantScoped
    @Transactional
    @Audited(action = "DOCUMENT_UPLOADED", entityType = "DOCUMENT")
    public DocumentSummaryResponse upload(UUID tenantId, MultipartFile file, String docTypeParam, UUID uploaderId) {
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
        Optional<String> ruleMatchedType = wasClassified
                ? docTypeClassifier.classify(tenantId, rawText)
                : Optional.empty();
        String docType;
        if (!wasClassified) {
            docType = docTypeParam.trim();
        } else if (ruleMatchedType.isPresent()) {
            docType = ruleMatchedType.get();
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

        boolean shouldAutoTrigger = status == DocumentStatus.TEXT_EXTRACTED
                && (ruleMatchedType.isPresent() || usesLlmExtraction(tenantId));
        if (shouldAutoTrigger) {
            try {
                extractionService.triggerExtraction(tenantId, doc.getId());
                doc = documentRepository.findById(doc.getId()).orElse(doc);
            } catch (RuntimeException e) {
                // Upload succeeding matters more than the auto-extraction convenience step;
                // the document just stays at TEXT_EXTRACTED and can still be extracted manually.
                log.warn("Auto-extraction after classification failed for document {}: {}", doc.getId(), e.toString());
            }
        }

        documentEventService.publishStatusChange(tenantId, doc.getId(), doc.getStatus(), doc.getDocType());
        return DocumentSummaryResponse.from(doc);
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

    @TenantScoped
    @Transactional(readOnly = true)
    public Page<DocumentSummaryResponse> list(UUID tenantId, String docType, Pageable pageable) {
        Page<Document> page = docType != null
                ? documentRepository.findByTenantIdAndDocTypeOrderByCreatedAtDesc(tenantId, docType, pageable)
                : documentRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
        return page.map(DocumentSummaryResponse::from);
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public DocumentSummaryResponse get(UUID tenantId, UUID documentId) {
        return DocumentSummaryResponse.from(requireDocument(documentId));
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
    public DocumentSummaryResponse updateDocType(UUID tenantId, UUID documentId, String newDocType) {
        if (newDocType == null || newDocType.isBlank()) {
            throw new ApiExceptions.ValidationException("Document type must not be blank");
        }
        Document doc = requireDocument(documentId);
        doc.setDocType(newDocType.trim());
        doc = documentRepository.save(doc);

        if (doc.getRawText() != null && !doc.getRawText().isBlank()) {
            try {
                extractionService.triggerExtraction(tenantId, doc.getId());
                doc = documentRepository.findById(doc.getId()).orElse(doc);
            } catch (RuntimeException e) {
                log.warn("Extraction after doc type change failed for document {}: {}", doc.getId(), e.toString());
            }
        }
        documentEventService.publishStatusChange(tenantId, doc.getId(), doc.getStatus(), doc.getDocType());
        return DocumentSummaryResponse.from(doc);
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
