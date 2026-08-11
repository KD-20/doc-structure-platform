package com.docstructure.platform.search;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** extracted_data is RLS-protected; callers must run these from a @TenantScoped method. */
public interface ExtractedDataRepository extends JpaRepository<ExtractedData, UUID> {
    List<ExtractedData> findByTenantIdAndDocumentIdOrderByCreatedAtDesc(UUID tenantId, UUID documentId);

    // jsonb_object_keys is a set-returning function, so this has to be native SQL rather than
    // JPQL. Field names come from whatever rule set (or the LLM strategy) produced them — this
    // lets the Search page's filter builder offer real field names instead of the user having to
    // remember them from a document's Structured Data tab. docType narrows to just that doc
    // type's fields when given; null returns every distinct field name across the tenant. q
    // narrows to names containing that substring (autocomplete as you type — "inv" matches
    // "invoice_number", "invoice_date"), null/blank returns everything.
    //
    // Keys have to be expanded into a subquery before either filtering by q or counting: a
    // set-returning function can't be referenced directly in an outer WHERE, and (separately)
    // can't be wrapped directly in count(DISTINCT ...) either — Postgres rejects both (the count
    // form was caught by a live 500, see chat history). Paged (not a plain List) so the dropdown
    // can fetch 5 at a time and "View more" rather than loading every field name up front.
    @Query(value = "SELECT key FROM (SELECT DISTINCT jsonb_object_keys(fields) AS key FROM extracted_data "
            + "WHERE tenant_id = :tenantId AND (:docType IS NULL OR doc_type = :docType)) t "
            + "WHERE (:q IS NULL OR key ILIKE '%' || :q || '%') ORDER BY key",
            countQuery = "SELECT count(*) FROM (SELECT DISTINCT jsonb_object_keys(fields) AS key FROM extracted_data "
                    + "WHERE tenant_id = :tenantId AND (:docType IS NULL OR doc_type = :docType)) t "
                    + "WHERE (:q IS NULL OR key ILIKE '%' || :q || '%')",
            nativeQuery = true)
    Page<String> findDistinctFieldNames(@Param("tenantId") UUID tenantId, @Param("docType") String docType,
                                         @Param("q") String q, Pageable pageable);
}
