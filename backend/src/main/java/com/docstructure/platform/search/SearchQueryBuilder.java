package com.docstructure.platform.search;

import com.docstructure.platform.common.ApiExceptions;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the shared WHERE clause (native SQL, named params) for both the search results query
 * and its count query. Field names and values are both bind parameters — including the JSONB
 * key on the left of `->`, which Postgres allows as a parameter — so this is not vulnerable to
 * SQL injection via filter field/value content.
 */
@Component
class SearchQueryBuilder {

    // gt/gte/lt/lte: numeric range filters ("total_amount" > "50") — a structured field's value
    // is always stored as JSONB text, so it's cast to numeric here rather than compared as text
    // (text comparison would sort "9" after "10"). The '~' regex guard runs before the cast so a
    // field that legitimately holds non-numeric text on some other document (or doc type) fails
    // the match cleanly instead of throwing a hard "invalid input syntax for type numeric" error
    // that would 500 the whole query.
    private static final Map<String, String> RANGE_OPERATORS =
            Map.of("gt", ">", "gte", ">=", "lt", "<", "lte", "<=");
    private static final String NUMERIC_PATTERN = "^-?[0-9]+(\\.[0-9]+)?$";

    // Sentinel field name meaning "don't require the caller to know which field the value lives
    // in — match if ANY extracted field on the document satisfies this operator/value." Iterates
    // every key in the fields JSONB via jsonb_each rather than a fixed field path; NULL-safe on
    // documents with no extracted_data at all (jsonb_each(NULL::jsonb) returns zero rows rather
    // than erroring — verified in psql), same as the named-field path already tolerates.
    static final String ANY_FIELD = "*";

    // "Fuzzy search": a semantic query is only considered an actual match above this cosine
    // similarity — pgvector's <=> operator is cosine *distance* (0 = identical, 2 = opposite),
    // so similarity is (1 - distance) and this is a floor on that, not the distance itself.
    // Below this, two documents just aren't meaningfully related; ranking them anyway (the old
    // behavior) let a semantic query silently degrade into "return everything, weakly sorted"
    // instead of "return what's actually similar." Not currently exposed as a request param —
    // one fixed threshold for every semantic query, matching what was asked for.
    static final double SEMANTIC_SIMILARITY_THRESHOLD = 0.70;

    // Floor on TsQueryExpr.RANK for a genuine full-text/fuzzy query (q, or a semantic query
    // running through the literal-text fallback when no embedding provider is configured —
    // see SearchService). Deliberately NOT the same value as SEMANTIC_SIMILARITY_THRESHOLD:
    // ts_rank/word_similarity use a much lower-valued scale than cosine similarity, and
    // TsQueryExpr's own SUBSTRING_MATCH tier is intentionally fixed at 0.3 as the weakest
    // legitimate match — a 0.70 floor here would exclude that entire tier and most genuine
    // ts_rank matches, leaving full-text search returning almost nothing. This is low enough
    // to keep real matches (including the 0.3 substring tier) while cutting near-zero noise.
    static final double FULLTEXT_MATCH_MIN_SCORE = 0.15;

    record BuiltPredicate(String sql, Map<String, Object> params) {
    }

    /**
     * restrictToDocumentIds is how guest search stays inside a guest link's scope — see
     * GuestAccessController. queryEmbeddingLiteral (a pgvector literal string, e.g.
     * "[0.1,0.2,...]") is non-null only when semantic search is actually available for this
     * query — it both binds the :queryEmbedding param (for ranking, see SearchService) and,
     * combined with the SEMANTIC_SIMILARITY_THRESHOLD filter below, restricts the candidate set
     * to documents that both have an embedding AND are genuinely similar to the query — not
     * just "have any embedding at all," which would let a barely-related document sneak in at
     * the bottom of a DESC rank ordering.
     */
    BuiltPredicate build(UUID tenantId, String docType, String q, List<SearchFilter> filters,
                          List<UUID> restrictToDocumentIds, String queryEmbeddingLiteral,
                          double textMatchMinScore) {
        StringBuilder where = new StringBuilder("d.tenant_id = :tenantId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);

        if (restrictToDocumentIds != null) {
            if (restrictToDocumentIds.isEmpty()) {
                where.append(" AND false");
            } else {
                StringBuilder idList = new StringBuilder();
                for (int i = 0; i < restrictToDocumentIds.size(); i++) {
                    if (i > 0) {
                        idList.append(", ");
                    }
                    String p = "restrictId" + i;
                    idList.append(':').append(p);
                    params.put(p, restrictToDocumentIds.get(i));
                }
                where.append(" AND d.id IN (").append(idList).append(")");
            }
        }
        if (docType != null && !docType.isBlank()) {
            where.append(" AND d.doc_type = :docType");
            params.put("docType", docType);
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND ").append(TsQueryExpr.MATCHES_QUERY)
                    .append(" AND (").append(TsQueryExpr.RANK).append(") >= :textMatchMinScore");
            params.put("q", q);
            params.put("textMatchMinScore", textMatchMinScore);
        }
        if (filters != null) {
            int i = 0;
            for (SearchFilter f : filters) {
                boolean anyField = ANY_FIELD.equals(f.field());
                String fieldParam = "field" + i;
                String valueParam = "value" + i;
                String rangeSqlOp = f.op() != null ? RANGE_OPERATORS.get(f.op().toLowerCase()) : null;
                if ("contains".equalsIgnoreCase(f.op())) {
                    if (anyField) {
                        where.append(" AND EXISTS (SELECT 1 FROM jsonb_each(ed.fields) AS kv(k, v) WHERE v->>'value' ILIKE :")
                                .append(valueParam).append(")");
                    } else {
                        where.append(" AND ed.fields->:").append(fieldParam).append("->>'value' ILIKE :").append(valueParam);
                        params.put(fieldParam, f.field());
                    }
                    params.put(valueParam, "%" + f.value() + "%");
                } else if ("fuzzy".equalsIgnoreCase(f.op())) {
                    // Same trigram machinery as full-text's TRIGRAM_MATCH (see TsQueryExpr),
                    // applied to one field's value instead of raw_text: word_similarity finds the
                    // best-matching extent of the field value against the typed term, so this
                    // tolerates case, typos, AND the field value being a much longer blob than
                    // just the word being searched for ("kurukshetra" against a multi-line
                    // address field) — none of which "equals" (exact, whole-value) or "contains"
                    // (exact substring) can do. word_similarity is case-insensitive here already
                    // (verified in psql), so no lower() needed on either side.
                    if (anyField) {
                        where.append(" AND EXISTS (SELECT 1 FROM jsonb_each(ed.fields) AS kv(k, v) WHERE word_similarity(:")
                                .append(valueParam).append(", coalesce(v->>'value', '')) > ")
                                .append(TsQueryExpr.TRIGRAM_THRESHOLD).append(")");
                    } else {
                        where.append(" AND word_similarity(:").append(valueParam)
                                .append(", coalesce(ed.fields->:").append(fieldParam).append("->>'value', '')) > ")
                                .append(TsQueryExpr.TRIGRAM_THRESHOLD);
                        params.put(fieldParam, f.field());
                    }
                    params.put(valueParam, f.value());
                } else if (rangeSqlOp != null) {
                    BigDecimal numericValue;
                    try {
                        numericValue = new BigDecimal(f.value());
                    } catch (NumberFormatException e) {
                        throw new ApiExceptions.ValidationException(
                                "Filter value for '" + f.field() + "' must be a number to use operator '" + f.op() + "'");
                    }
                    if (anyField) {
                        where.append(" AND EXISTS (SELECT 1 FROM jsonb_each(ed.fields) AS kv(k, v) WHERE v->>'value' ~ '")
                                .append(NUMERIC_PATTERN).append("' AND (v->>'value')::numeric ")
                                .append(rangeSqlOp).append(" :").append(valueParam).append(")");
                    } else {
                        where.append(" AND ed.fields->:").append(fieldParam).append("->>'value' ~ '")
                                .append(NUMERIC_PATTERN).append("'")
                                .append(" AND (ed.fields->:").append(fieldParam).append("->>'value')::numeric ")
                                .append(rangeSqlOp).append(" :").append(valueParam);
                        params.put(fieldParam, f.field());
                    }
                    params.put(valueParam, numericValue);
                } else {
                    if (anyField) {
                        where.append(" AND EXISTS (SELECT 1 FROM jsonb_each(ed.fields) AS kv(k, v) WHERE v->>'value' = :")
                                .append(valueParam).append(")");
                    } else {
                        where.append(" AND ed.fields->:").append(fieldParam).append("->>'value' = :").append(valueParam);
                        params.put(fieldParam, f.field());
                    }
                    params.put(valueParam, f.value());
                }
                i++;
            }
        }
        if (queryEmbeddingLiteral != null) {
            where.append(" AND ed.embedding IS NOT NULL")
                    .append(" AND (1 - (ed.embedding <=> CAST(:queryEmbedding AS vector))) > :minSimilarity");
            params.put("queryEmbedding", queryEmbeddingLiteral);
            params.put("minSimilarity", SEMANTIC_SIMILARITY_THRESHOLD);
        }
        return new BuiltPredicate(where.toString(), params);
    }
}
