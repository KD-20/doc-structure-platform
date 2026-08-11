package com.docstructure.platform.rules;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** default_rule_sets is global reference data, not RLS-protected — no tenant scoping needed. */
public interface DefaultRuleSetRepository extends JpaRepository<DefaultRuleSet, UUID> {
    Optional<DefaultRuleSet> findByDocType(String docType);

    List<DefaultRuleSet> findAllByOrderByDocTypeAsc();
}
