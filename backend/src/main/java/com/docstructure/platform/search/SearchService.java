package com.docstructure.platform.search;

import com.docstructure.platform.audit.AuditService;
import com.docstructure.platform.common.TenantScoped;
import com.docstructure.platform.documents.Document;
import com.docstructure.platform.documents.DocumentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Two-step lookup rather than one native query returning every column: the id/rank query
 * only ever returns UUID/numeric columns (safe, unambiguous native-query type mapping);
 * Document/ExtractedData are then fetched as proper typed JPA entities. This trades a couple
 * of extra queries (fine at v1 scale) for not having to guess how Hibernate's native-query
 * path maps jsonb/timestamptz columns into Object[] — see docs/DECISIONS.md.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final EntityManager entityManager;
    private final EmbeddingProvider embeddingProvider;
    private final SearchQueryBuilder queryBuilder;
    private final DocumentRepository documentRepository;
    private final ExtractedDataRepository extractedDataRepository;
    private final AuditService auditService;

    public SearchService(EntityManager entityManager, EmbeddingProvider embeddingProvider,
                          SearchQueryBuilder queryBuilder, DocumentRepository documentRepository,
                          ExtractedDataRepository extractedDataRepository, AuditService auditService) {
        this.entityManager = entityManager;
        this.embeddingProvider = embeddingProvider;
        this.queryBuilder = queryBuilder;
        this.documentRepository = documentRepository;
        this.extractedDataRepository = extractedDataRepository;
        this.auditService = auditService;
    }

    /**
     * The Search page's entry point — every call here is recorded to audit_log (action
     * SEARCH_PERFORMED, entityType SEARCH, no entityId since a search isn't "about" one row).
     * Reverses the original v1 decision to skip search from the audit log (see
     * docs/DECISIONS.md #9a) per explicit request; guest search (searchWithinDocuments) stays
     * unaudited, unchanged from that same decision — this is specifically about the Search page.
     */
    @TenantScoped
    @Transactional
    public SearchResponse search(UUID tenantId, String docType, String q, List<SearchFilter> filters,
                                  String semanticQuery, int page, int size) {
        SearchResponse response = searchImpl(tenantId, docType, q, filters, null, semanticQuery, page, size);
        auditService.record("SEARCH_PERFORMED", "SEARCH", null, Map.of(
                "q", q != null ? q : "",
                "docType", docType != null ? docType : "",
                "semanticQuery", semanticQuery != null ? semanticQuery : "",
                "filters", filters != null ? filters : List.of(),
                "page", page,
                "size", size,
                "resultCount", response.totalElements()));
        log.debug("search executed tenant={} docType={} q={} semantic={} results={}", tenantId, docType, q,
                response.semanticSearchAvailable(), response.totalElements());
        return response;
    }

    /** Guest search: same query path, but hard-restricted to the guest link's scoped document ids. */
    @TenantScoped
    @Transactional(readOnly = true)
    public SearchResponse searchWithinDocuments(UUID tenantId, String q, List<UUID> allowedDocumentIds,
                                                 int page, int size) {
        return searchImpl(tenantId, null, q, List.of(), allowedDocumentIds, null, page, size);
    }

    // Private helper, not a separate transactional/tenant-scoped unit: it always runs inside
    // whichever public method above already opened the transaction and set tenant context.
    private SearchResponse searchImpl(UUID tenantId, String docType, String q, List<SearchFilter> filters,
                                       List<UUID> restrictToDocumentIds, String semanticQuery, int page, int size) {
        boolean semanticRequested = semanticQuery != null && !semanticQuery.isBlank();
        Optional<float[]> queryEmbedding = semanticRequested && embeddingProvider.isEnabled()
                ? embeddingProvider.embed(semanticQuery)
                : Optional.empty();
        boolean semanticAvailable = semanticRequested && queryEmbedding.isPresent();

        // No real embeddings to rank by: fall back to running the semantic query as a literal
        // full-text search rather than silently returning nothing (a "search for X" that finds
        // zero results when X is right there in the document text is worse than an honest,
        // clearly-flagged approximation — semanticSearchAvailable still reports false either
        // way, see SearchPage's disclaimer). Doesn't override an explicit q the user also typed.
        String effectiveQ = (q != null && !q.isBlank()) ? q
                : (semanticRequested && !semanticAvailable) ? semanticQuery : q;

        if (effectiveQ != null && !effectiveQ.isBlank()) {
            // Postgres's own default (0.6) misses common typos like letter transpositions
            // ("invocie" vs "invoice" scores 0.5) — SET LOCAL keeps the lower threshold scoped
            // to this transaction only, and (unlike calling word_similarity() as a plain
            // function) still lets the <% operator use the trgm GIN index. See TsQueryExpr.
            entityManager.createNativeQuery(
                    "SET LOCAL pg_trgm.word_similarity_threshold = " + TsQueryExpr.TRIGRAM_THRESHOLD)
                    .executeUpdate();
        }

        String queryEmbeddingLiteral = semanticAvailable ? VectorLiterals.toLiteral(queryEmbedding.get()) : null;
        SearchQueryBuilder.BuiltPredicate predicate =
                queryBuilder.build(tenantId, docType, effectiveQ, filters, restrictToDocumentIds, queryEmbeddingLiteral);

        String textRankExpr = (effectiveQ != null && !effectiveQ.isBlank())
                ? TsQueryExpr.RANK
                : "0";
        String rankExpr;
        if (semanticAvailable) {
            String semanticExpr = "(1 - (ed.embedding <=> CAST(:queryEmbedding AS vector)))";
            rankExpr = (q != null && !q.isBlank())
                    ? "(0.5 * " + textRankExpr + " + 0.5 * " + semanticExpr + ")"
                    : semanticExpr;
        } else {
            rankExpr = textRankExpr;
        }

        String idSql = "SELECT d.id, " + rankExpr + " AS text_rank FROM documents d "
                + "LEFT JOIN LATERAL (SELECT * FROM extracted_data e WHERE e.document_id = d.id "
                + "ORDER BY e.created_at DESC LIMIT 1) ed ON true "
                + "WHERE " + predicate.sql()
                + " ORDER BY text_rank DESC, d.created_at DESC LIMIT :limit OFFSET :offset";

        Query idQuery = entityManager.createNativeQuery(idSql);
        predicate.params().forEach(idQuery::setParameter);
        idQuery.setParameter("limit", size);
        idQuery.setParameter("offset", (long) page * size);

        @SuppressWarnings("unchecked")
        List<Object[]> idRows = idQuery.getResultList();

        Map<UUID, Double> rankById = new LinkedHashMap<>();
        for (Object[] row : idRows) {
            rankById.put((UUID) row[0], ((Number) row[1]).doubleValue());
        }

        String countSql = "SELECT count(*) FROM documents d "
                + "LEFT JOIN LATERAL (SELECT * FROM extracted_data e WHERE e.document_id = d.id "
                + "ORDER BY e.created_at DESC LIMIT 1) ed ON true "
                + "WHERE " + predicate.sql();
        Query countQuery = entityManager.createNativeQuery(countSql);
        predicate.params().forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        Map<UUID, Document> documentsById = new HashMap<>();
        documentRepository.findAllById(rankById.keySet()).forEach(d -> documentsById.put(d.getId(), d));

        List<SearchResultItem> items = rankById.entrySet().stream()
                .map(entry -> toResultItem(entry.getKey(), entry.getValue(), documentsById, tenantId))
                .filter(item -> item != null)
                .toList();

        return new SearchResponse(items, total, page, size, semanticRequested, semanticAvailable);
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public List<String> docTypes(UUID tenantId) {
        return documentRepository.findDistinctDocTypesByTenantId(tenantId);
    }

    /** Lets the filter builder offer real field names instead of the user guessing them. */
    @TenantScoped
    @Transactional(readOnly = true)
    public Page<String> fieldNames(UUID tenantId, String docType, String q, Pageable pageable) {
        return extractedDataRepository.findDistinctFieldNames(tenantId, docType, q != null && !q.isBlank() ? q : null,
                pageable);
    }

    private SearchResultItem toResultItem(UUID documentId, double rank, Map<UUID, Document> documentsById, UUID tenantId) {
        Document doc = documentsById.get(documentId);
        if (doc == null) {
            return null;
        }
        ExtractedData latest = extractedDataRepository.findByTenantIdAndDocumentIdOrderByCreatedAtDesc(tenantId, documentId)
                .stream().findFirst().orElse(null);
        return new SearchResultItem(doc.getId(), doc.getFilename(), doc.getContentType(), doc.getDocType(), doc.getStatus(), rank,
                latest != null ? latest.getFields() : null,
                latest != null && latest.getOverallConfidence() != null ? latest.getOverallConfidence().doubleValue() : null,
                doc.getCreatedAt());
    }
}
