package com.docstructure.platform.extraction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** extraction_runs is RLS-protected; callers must run these from a @TenantScoped method. */
public interface ExtractionRunRepository extends JpaRepository<ExtractionRun, UUID> {
    List<ExtractionRun> findByTenantIdAndDocumentIdOrderByCreatedAtDesc(UUID tenantId, UUID documentId);

    /** Single-document lookup — used wherever one DocumentSummaryResponse is built at a time (get/upload/updateDocType). */
    Optional<ExtractionRun> findFirstByTenantIdAndDocumentIdOrderByCreatedAtDesc(UUID tenantId, UUID documentId);

    /** DocumentDocumentId/status projection for {@link #findLatestStatusByDocumentIds}. */
    interface LatestRunStatus {
        UUID getDocumentId();

        String getStatus();
    }

    /**
     * One row per document — its single most recent run's status — for a whole page of
     * documents at once (see DocumentService#list), instead of one query per row. DISTINCT ON
     * is Postgres-specific (no standard JPQL equivalent), hence native.
     */
    @Query(value = "SELECT DISTINCT ON (document_id) document_id AS documentId, status AS status "
            + "FROM extraction_runs WHERE tenant_id = :tenantId AND document_id IN (:documentIds) "
            + "ORDER BY document_id, created_at DESC", nativeQuery = true)
    List<LatestRunStatus> findLatestStatusByDocumentIds(@Param("tenantId") UUID tenantId,
                                                          @Param("documentIds") Collection<UUID> documentIds);
}
