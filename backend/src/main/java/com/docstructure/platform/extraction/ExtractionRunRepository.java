package com.docstructure.platform.extraction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** extraction_runs is RLS-protected; callers must run these from a @TenantScoped method. */
public interface ExtractionRunRepository extends JpaRepository<ExtractionRun, UUID> {
    List<ExtractionRun> findByTenantIdAndDocumentIdOrderByCreatedAtDesc(UUID tenantId, UUID documentId);
}
