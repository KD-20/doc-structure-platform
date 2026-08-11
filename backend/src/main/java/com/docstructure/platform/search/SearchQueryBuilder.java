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

    record BuiltPredicate(String sql, Map<String, Object> params) {
    }

    /**
     * restrictToDocumentIds is how guest search stays inside a guest link's scope — see
     * GuestAccessController. queryEmbeddingLiteral (a pgvector literal string, e.g.
     * "[0.1,0.2,...]") is non-null only when semantic search is actually available for this
     * query — it both binds the :queryEmbedding param (for ranking, see SearchService) and
     * excludes documents with no embedding yet from the candidate set, so they can't sort
     * ahead of real matches under a DESC rank ordering.
     */
    BuiltPredicate build(UUID tenantId, String docType, String q, List<SearchFilter> filters,
                          List<UUID> restrictToDocumentIds, String queryEmbeddingLiteral) {
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
            where.append(" AND ").append(TsQueryExpr.MATCHES_QUERY);
            params.put("q", q);
        }
        if (filters != null) {
            int i = 0;
            for (SearchFilter f : filters) {
                String fieldParam = "field" + i;
                String valueParam = "value" + i;
                String rangeSqlOp = f.op() != null ? RANGE_OPERATORS.get(f.op().toLowerCase()) : null;
                if ("contains".equalsIgnoreCase(f.op())) {
                    where.append(" AND ed.fields->:").append(fieldParam).append("->>'value' ILIKE :").append(valueParam);
                    params.put(fieldParam, f.field());
                    params.put(valueParam, "%" + f.value() + "%");
                } else if (rangeSqlOp != null) {
                    BigDecimal numericValue;
                    try {
                        numericValue = new BigDecimal(f.value());
                    } catch (NumberFormatException e) {
                        throw new ApiExceptions.ValidationException(
                                "Filter value for '" + f.field() + "' must be a number to use operator '" + f.op() + "'");
                    }
                    where.append(" AND ed.fields->:").append(fieldParam).append("->>'value' ~ '")
                            .append(NUMERIC_PATTERN).append("'")
                            .append(" AND (ed.fields->:").append(fieldParam).append("->>'value')::numeric ")
                            .append(rangeSqlOp).append(" :").append(valueParam);
                    params.put(fieldParam, f.field());
                    params.put(valueParam, numericValue);
                } else {
                    where.append(" AND ed.fields->:").append(fieldParam).append("->>'value' = :").append(valueParam);
                    params.put(fieldParam, f.field());
                    params.put(valueParam, f.value());
                }
                i++;
            }
        }
        if (queryEmbeddingLiteral != null) {
            where.append(" AND ed.embedding IS NOT NULL");
            params.put("queryEmbedding", queryEmbeddingLiteral);
        }
        return new BuiltPredicate(where.toString(), params);
    }
}
