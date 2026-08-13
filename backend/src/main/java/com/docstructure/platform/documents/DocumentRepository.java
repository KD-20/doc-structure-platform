package com.docstructure.platform.documents;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** documents is RLS-protected; callers must run these from a @TenantScoped method. */
public interface DocumentRepository extends JpaRepository<Document, UUID> {
    // Explicit ordering, not left to chance: without it, page order isn't guaranteed
    // consistent across requests (no ORDER BY means the DB is free to return rows in
    // whatever order it finds convenient), which breaks pagination — page 2 could show
    // documents already seen on page 1, or skip some entirely.
    Page<Document> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    Page<Document> findByTenantIdAndDocTypeOrderByCreatedAtDesc(UUID tenantId, String docType, Pageable pageable);

    // Bulk re-extraction (see BulkReextractionService) — every document of a doc type, unpaged,
    // since this feeds a loop that enqueues an extraction run per document rather than a UI page.
    List<Document> findByTenantIdAndDocType(UUID tenantId, String docType);

    @Query("SELECT DISTINCT d.docType FROM Document d WHERE d.tenantId = :tenantId ORDER BY d.docType")
    List<String> findDistinctDocTypesByTenantId(@Param("tenantId") UUID tenantId);

    // Anonymous trial uploads (see PublicDemoService) — scoped by device id within the one
    // shared public-demo tenant, not by any real membership/login.
    long countByTenantIdAndDeviceId(UUID tenantId, String deviceId);

    List<Document> findByTenantIdAndDeviceIdOrderByCreatedAtDesc(UUID tenantId, String deviceId);
}
