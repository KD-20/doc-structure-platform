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
import com.docstructure.platform.search.ExtractedDataStatus;
import com.docstructure.platform.search.VectorLiterals;
import com.docstructure.platform.tenancy.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Split in two: {@link #enqueueExtraction} does only the fast, synchronous part (validation,
 * strategy resolution, creating a PENDING run) and returns immediately; the actual extraction
 * work — {@link #performExtraction} — runs later, off the request thread, picked up by
 * ExtractionWorker once the enqueueing transaction commits. This is exactly the split the
 * original synchronous v1 design anticipated: PENDING/RUNNING were always part of the status
 * model, just never actually observed by a client until now — see docs/DECISIONS.md.
 */
@Service
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

    private final DocumentRepository documentRepository;
    private final TenantRepository tenantRepository;
    private final ExtractionRunRepository runRepository;
    private final ExtractedDataRepository extractedDataRepository;
    private final ExtractionStrategy extractionStrategy;
    private final RuleSetService ruleSetService;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final DocumentEventService documentEventService;
    private final EmbeddingProvider embeddingProvider;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final ExtractionFailureRecorder failureRecorder;

    public ExtractionService(DocumentRepository documentRepository, TenantRepository tenantRepository,
                              ExtractionRunRepository runRepository, ExtractedDataRepository extractedDataRepository,
                              ExtractionStrategy extractionStrategy, RuleSetService ruleSetService,
                              ObjectMapper objectMapper, AuditService auditService,
                              DocumentEventService documentEventService, EmbeddingProvider embeddingProvider,
                              EntityManager entityManager, ApplicationEventPublisher eventPublisher,
                              MeterRegistry meterRegistry, ExtractionFailureRecorder failureRecorder) {
        this.documentRepository = documentRepository;
        this.tenantRepository = tenantRepository;
        this.runRepository = runRepository;
        this.extractedDataRepository = extractedDataRepository;
        this.extractionStrategy = extractionStrategy;
        this.ruleSetService = ruleSetService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.documentEventService = documentEventService;
        this.embeddingProvider = embeddingProvider;
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
        this.failureRecorder = failureRecorder;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Fast path, runs synchronously in the caller's request: validates the document/tenant,
     * creates the PENDING run, and publishes ExtractionRequestedEvent for ExtractionWorker to
     * pick up once this transaction commits.
     */
    @TenantScoped
    @Transactional
    public ExtractionRunResponse enqueueExtraction(UUID tenantId, UUID documentId, UUID triggeredByUserId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Document not found"));
        if (document.getRawText() == null || document.getRawText().isBlank()) {
            throw new ApiExceptions.ValidationException("Document has no extracted text to structure yet");
        }
        if (!tenantRepository.existsById(tenantId)) {
            throw new ApiExceptions.NotFoundException("Tenant not found");
        }

        ExtractionRun run = new ExtractionRun();
        run.setTenantId(tenantId);
        run.setDocumentId(documentId);
        run.setStrategy(ExtractionStrategyType.RULE_BASED);
        run.setStatus(ExtractionRunStatus.PENDING);
        run = runRepository.save(run);

        log.info("extraction enqueued run={} document={} tenant={} triggeredBy={}",
                run.getId(), documentId, tenantId, triggeredByUserId);
        documentEventService.publishExtractionStatus(tenantId, documentId, ExtractionRunStatus.PENDING);
        eventPublisher.publishEvent(new ExtractionRequestedEvent(tenantId, documentId, run.getId(), triggeredByUserId));
        return ExtractionRunResponse.from(run);
    }

    /**
     * The actual work, formerly the whole of the old synchronous triggerExtraction — now only
     * ever called by ExtractionWorker, on a background thread, against a run row that
     * enqueueExtraction already created and committed. Never throws: every failure mode is
     * caught and recorded as a FAILED run, since there's no HTTP caller left to propagate an
     * exception to by the time this runs.
     */
    @TenantScoped
    @Transactional
    public void performExtraction(UUID tenantId, UUID documentId, UUID runId) {
        ExtractionRun run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            log.warn("extraction run={} not found when worker picked it up (tenant={}, document={}) — dropping",
                    runId, tenantId, documentId);
            return;
        }
        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            log.warn("document={} not found when running extraction run={} (tenant={})", documentId, runId, tenantId);
            failureRecorder.recordFailure(tenantId, documentId, runId, "Document not found");
            return;
        }
        if (!tenantRepository.existsById(tenantId)) {
            log.warn("tenant={} not found when running extraction run={}", tenantId, runId);
            failureRecorder.recordFailure(tenantId, documentId, runId, "Tenant not found");
            return;
        }

        log.info("extraction started run={} document={} tenant={} strategy={}", runId, documentId, tenantId,
                run.getStrategy());
        run.setStatus(ExtractionRunStatus.RUNNING);
        run.setStartedAt(Instant.now());
        runRepository.save(run);
        documentEventService.publishExtractionStatus(tenantId, documentId, ExtractionRunStatus.RUNNING);
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // Considers the tenant's own active rule set first, falling back to a
            // platform-shipped default for this doc type when the tenant has none — see
            // RuleSetService#resolveDefinition. ruleSetId stays null both when only a default
            // applies (it references extraction_rule_sets, tenant rows only) and when no rule
            // set exists at all — RuleBasedExtractionStrategy no longer treats that second case
            // as a failure (see its own javadoc), it just returns an UNSTRUCTURED result with no
            // fields, and this run still runs to completion.
            run.setRuleSetId(ruleSetService.findActive(tenantId, document.getDocType())
                    .map(rs -> rs.getId())
                    .orElse(null));

            ExtractionContext context = new ExtractionContext(tenantId, documentId, document.getDocType(),
                    document.getRawText());
            ExtractionResult result = extractionStrategy.extract(context);
            boolean isStructured = result.status() != ExtractedDataStatus.UNSTRUCTURED;

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

            // STRUCTURED specifically means "fields were extracted" — a document with no
            // matching rule set stays at TEXT_EXTRACTED even though it now has an embedding and
            // is findable via semantic/fuzzy search; STRUCTURED would overclaim what happened.
            document.setStatus(isStructured ? DocumentStatus.STRUCTURED : DocumentStatus.TEXT_EXTRACTED);
            documentRepository.save(document);
            auditService.record("EXTRACTION_RUN_COMPLETED", "EXTRACTION_RUN", run.getId(),
                    Map.of("documentId", documentId, "status", "SUCCEEDED", "structured", isStructured));
            documentEventService.publishStatusChange(tenantId, documentId, document.getStatus(), document.getDocType());
            documentEventService.publishExtractionStatus(tenantId, documentId, ExtractionRunStatus.SUCCEEDED);
            log.info("extraction succeeded run={} document={} tenant={} structured={} confidence={}", runId,
                    documentId, tenantId, isStructured, result.overallConfidence());
            recordExtractionMetrics(sample, run.getStrategy(), ExtractionRunStatus.SUCCEEDED);
        } catch (RuntimeException e) {
            log.warn("extraction failed run={} document={} tenant={}: {}", runId, documentId, tenantId, e.toString());
            recordExtractionMetrics(sample, run.getStrategy(), ExtractionRunStatus.FAILED);
            // Rethrow rather than recording FAILED here: the RUNNING update above already holds
            // a row lock in THIS (still-open) transaction, and ExtractionFailureRecorder's own
            // REQUIRES_NEW transaction updating the very same row would block on that lock
            // forever — a same-thread self-deadlock, confirmed live (two backends stuck on
            // "Lock/transactionid" against the same extraction_runs row, a third stuck behind
            // both). Rethrowing lets this transaction actually finish (roll back) first, so by
            // the time ExtractionWorker's catch calls recordFailure, the lock is gone.
            throw e;
        }
    }

    private void recordExtractionMetrics(Timer.Sample sample, ExtractionStrategyType strategy,
                                          ExtractionRunStatus status) {
        sample.stop(meterRegistry.timer("extraction.duration", "strategy", strategy.name(), "status", status.name()));
        meterRegistry.counter("extraction.runs", "strategy", strategy.name(), "status", status.name()).increment();
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
            log.warn("embedding generation returned no result for extracted_data={}", extractedDataId);
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
            log.debug("embedding written extracted_data={} model={}", extractedDataId, embeddingProvider.modelName());
        } catch (RuntimeException e) {
            log.warn("failed to persist embedding for extracted_data={}: {}", extractedDataId, e.toString());
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
