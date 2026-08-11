package com.docstructure.platform.extraction;

import com.docstructure.platform.audit.AuditService;
import com.docstructure.platform.common.ApiExceptions;
import com.docstructure.platform.common.TenantScoped;
import com.docstructure.platform.documents.Document;
import com.docstructure.platform.documents.DocumentEventService;
import com.docstructure.platform.documents.DocumentRepository;
import com.docstructure.platform.documents.DocumentStatus;
import com.docstructure.platform.rules.RuleSetService;
import com.docstructure.platform.search.EmbeddingProvider;
import com.docstructure.platform.search.ExtractedData;
import com.docstructure.platform.search.ExtractedDataRepository;
import com.docstructure.platform.search.VectorLiterals;
import com.docstructure.platform.tenancy.Tenant;
import com.docstructure.platform.tenancy.TenantRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * v1 runs extraction synchronously inline (PENDING is never actually observed by a client —
 * see docs/DECISIONS.md); the schema/status model already supports a real async worker
 * picking up PENDING runs later without any API changes.
 */
@Service
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

    private final DocumentRepository documentRepository;
    private final TenantRepository tenantRepository;
    private final ExtractionRunRepository runRepository;
    private final ExtractedDataRepository extractedDataRepository;
    private final ExtractionStrategyFactory strategyFactory;
    private final RuleSetService ruleSetService;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final DocumentEventService documentEventService;
    private final EmbeddingProvider embeddingProvider;
    private final EntityManager entityManager;

    public ExtractionService(DocumentRepository documentRepository, TenantRepository tenantRepository,
                              ExtractionRunRepository runRepository, ExtractedDataRepository extractedDataRepository,
                              ExtractionStrategyFactory strategyFactory, RuleSetService ruleSetService,
                              ObjectMapper objectMapper, AuditService auditService,
                              DocumentEventService documentEventService, EmbeddingProvider embeddingProvider,
                              EntityManager entityManager) {
        this.documentRepository = documentRepository;
        this.tenantRepository = tenantRepository;
        this.runRepository = runRepository;
        this.extractedDataRepository = extractedDataRepository;
        this.strategyFactory = strategyFactory;
        this.ruleSetService = ruleSetService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.documentEventService = documentEventService;
        this.embeddingProvider = embeddingProvider;
        this.entityManager = entityManager;
    }

    @TenantScoped
    @Transactional
    public ExtractionRunResponse triggerExtraction(UUID tenantId, UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Document not found"));
        if (document.getRawText() == null || document.getRawText().isBlank()) {
            throw new ApiExceptions.ValidationException("Document has no extracted text to structure yet");
        }
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Tenant not found"));

        // Misconfiguration (e.g. tenant set to LLM while platform.extraction.llm.enabled=false)
        // fails loudly here, before an ExtractionRun row even exists — that's a deployment
        // issue, not a per-document outcome to record and move on from.
        ExtractionStrategy strategy = strategyFactory.resolve(tenant.getSettings());
        ExtractionStrategyType strategyType = strategy instanceof LlmExtractionStrategy
                ? ExtractionStrategyType.LLM : ExtractionStrategyType.RULE_BASED;

        ExtractionRun run = new ExtractionRun();
        run.setTenantId(tenantId);
        run.setDocumentId(documentId);
        run.setStrategy(strategyType);
        run.setStartedAt(Instant.now());

        try {
            if (strategyType == ExtractionStrategyType.RULE_BASED) {
                // resolveDefinition (non-throwing), not getActive: a nested @Transactional
                // method throwing marks the whole transaction rollback-only in Spring even
                // though we catch it right here — see RuleSetService#findActive javadoc.
                // Considers the tenant's own active rule set first, falling back to a
                // platform-shipped default for this doc type when the tenant has none — see
                // RuleSetService#resolveDefinition. ruleSetId stays null when only a default
                // applies, since it references extraction_rule_sets (tenant rows only).
                if (ruleSetService.resolveDefinition(tenantId, document.getDocType()).isEmpty()) {
                    throw new ApiExceptions.NotFoundException(
                            "No active rule set for doc type '" + document.getDocType() + "'");
                }
                run.setRuleSetId(ruleSetService.findActive(tenantId, document.getDocType())
                        .map(rs -> rs.getId())
                        .orElse(null));
            }

            ExtractionContext context = new ExtractionContext(tenantId, documentId, document.getDocType(),
                    document.getRawText());
            ExtractionResult result = strategy.extract(context);

            run.setStatus(ExtractionRunStatus.SUCCEEDED);
            run.setCompletedAt(Instant.now());
            run = runRepository.save(run);

            ExtractedData data = new ExtractedData();
            data.setTenantId(tenantId);
            data.setDocumentId(documentId);
            data.setExtractionRunId(run.getId());
            data.setDocType(document.getDocType());
            data.setFields(objectMapper.valueToTree(result.fields()));
            data.setOverallConfidence(BigDecimal.valueOf(result.overallConfidence()));
            data.setStatus(result.status());
            data = extractedDataRepository.save(data);
            writeEmbedding(data.getId(), document.getRawText());

            document.setStatus(DocumentStatus.STRUCTURED);
            documentRepository.save(document);
            auditService.record("EXTRACTION_RUN_COMPLETED", "EXTRACTION_RUN", run.getId(),
                    Map.of("documentId", documentId, "status", "SUCCEEDED"));
            documentEventService.publishStatusChange(tenantId, documentId, document.getStatus(), document.getDocType());
            return ExtractionRunResponse.from(run);
        } catch (RuntimeException e) {
            run.setStatus(ExtractionRunStatus.FAILED);
            run.setCompletedAt(Instant.now());
            run.setErrorMessage(e.getMessage());
            runRepository.save(run);
            document.setStatus(DocumentStatus.STRUCTURING_FAILED);
            documentRepository.save(document);
            auditService.record("EXTRACTION_RUN_FAILED", "EXTRACTION_RUN", run.getId(),
                    Map.of("documentId", documentId, "status", "FAILED", "error", String.valueOf(e.getMessage())));
            documentEventService.publishStatusChange(tenantId, documentId, document.getStatus(), document.getDocType());
            return ExtractionRunResponse.from(run);
        }
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public List<ExtractionRunResponse> listRuns(UUID tenantId, UUID documentId) {
        return runRepository.findByTenantIdAndDocumentIdOrderByCreatedAtDesc(tenantId, documentId).stream()
                .map(ExtractionRunResponse::from)
                .toList();
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public ExtractionRunResponse getRun(UUID tenantId, UUID runId) {
        return runRepository.findById(runId)
                .map(ExtractionRunResponse::from)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Extraction run not found"));
    }

    /**
     * Native UPDATE, not a mapped JPA field (see ExtractedData javadoc) — this is the only
     * place a vector is ever written, so a full Hibernate/pgvector type isn't worth the
     * dependency. Best-effort: embeddings power semantic search ranking, not extraction
     * correctness, so a failure here logs and moves on rather than failing the run.
     */
    private void writeEmbedding(UUID extractedDataId, String rawText) {
        if (!embeddingProvider.isEnabled()) {
            return;
        }
        Optional<float[]> vector = embeddingProvider.embed(rawText);
        if (vector.isEmpty()) {
            log.warn("Embedding generation returned no result for extracted_data {}", extractedDataId);
            return;
        }
        try {
            entityManager.createNativeQuery(
                            "UPDATE extracted_data SET embedding = CAST(:embedding AS vector), "
                                    + "embedding_model = :model WHERE id = :id")
                    .setParameter("embedding", VectorLiterals.toLiteral(vector.get()))
                    .setParameter("model", embeddingProvider.modelName())
                    .setParameter("id", extractedDataId)
                    .executeUpdate();
        } catch (RuntimeException e) {
            log.warn("Failed to persist embedding for extracted_data {}: {}", extractedDataId, e.toString());
        }
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public List<ExtractedDataResponse> getExtractedData(UUID tenantId, UUID documentId) {
        return extractedDataRepository.findByTenantIdAndDocumentIdOrderByCreatedAtDesc(tenantId, documentId).stream()
                .map(d -> new ExtractedDataResponse(d.getId(), d.getDocumentId(), d.getDocType(), d.getFields(),
                        d.getOverallConfidence() != null ? d.getOverallConfidence().doubleValue() : 0.0,
                        d.getStatus().name(), d.getCreatedAt()))
                .toList();
    }
}
