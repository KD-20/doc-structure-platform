package com.docstructure.platform.rules;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** extraction_rule_sets is RLS-protected; callers must run these from a @TenantScoped method. */
public interface ExtractionRuleSetRepository extends JpaRepository<ExtractionRuleSet, UUID> {
    List<ExtractionRuleSet> findByTenantIdOrderByDocTypeAscVersionDesc(UUID tenantId);

    /** Used by DocTypeClassifier to score a document's text against every candidate doc type at once. */
    List<ExtractionRuleSet> findByTenantIdAndActiveTrue(UUID tenantId);

    Optional<ExtractionRuleSet> findByTenantIdAndDocTypeAndActiveTrue(UUID tenantId, String docType);

    Optional<ExtractionRuleSet> findByTenantIdAndDocTypeAndVersion(UUID tenantId, String docType, int version);

    Optional<ExtractionRuleSet> findTopByTenantIdAndDocTypeOrderByVersionDesc(UUID tenantId, String docType);
}
