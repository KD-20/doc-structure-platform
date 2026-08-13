package com.docstructure.platform.rules;

import com.docstructure.platform.audit.Audited;
import com.docstructure.platform.common.ApiExceptions;
import com.docstructure.platform.common.TenantScoped;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RuleSetService {

    private static final Logger log = LoggerFactory.getLogger(RuleSetService.class);

    private final ExtractionRuleSetRepository repository;
    private final DefaultRuleSetRepository defaultRuleSetRepository;
    private final RuleInterpreter ruleInterpreter;
    private final ObjectMapper objectMapper;

    public RuleSetService(ExtractionRuleSetRepository repository, DefaultRuleSetRepository defaultRuleSetRepository,
                           RuleInterpreter ruleInterpreter, ObjectMapper objectMapper) {
        this.repository = repository;
        this.defaultRuleSetRepository = defaultRuleSetRepository;
        this.ruleInterpreter = ruleInterpreter;
        this.objectMapper = objectMapper;
    }

    /**
     * Always creates a new immutable version and makes it the active one; never mutates an
     * existing row. saveAndFlush on the deactivation, same reasoning as activateVersion: the
     * new row's INSERT (is_active=true) and the old row's UPDATE (is_active=false) both touch
     * ux_rule_sets_active, a real (non-deferrable) unique index, and Hibernate doesn't
     * guarantee flushing them in call order — flushing the deactivation immediately avoids
     * ever risking two rows reading as active at once.
     */
    @TenantScoped
    @Transactional
    @Audited(action = "RULE_SET_CREATED", entityType = "RULE_SET")
    public ExtractionRuleSet createVersion(UUID tenantId, String docType, RuleSetDefinition definition, UUID userId) {
        if (!docType.equals(definition.docType())) {
            throw new ApiExceptions.ValidationException("Path docType and definition.docType must match");
        }
        repository.findByTenantIdAndDocTypeAndActiveTrue(tenantId, docType)
                .ifPresent(current -> {
                    current.setActive(false);
                    repository.saveAndFlush(current);
                });
        int nextVersion = repository.findTopByTenantIdAndDocTypeOrderByVersionDesc(tenantId, docType)
                .map(rs -> rs.getVersion() + 1)
                .orElse(1);

        ExtractionRuleSet ruleSet = new ExtractionRuleSet();
        ruleSet.setTenantId(tenantId);
        ruleSet.setDocType(docType);
        ruleSet.setVersion(nextVersion);
        ruleSet.setDefinition(objectMapper.valueToTree(definition));
        ruleSet.setActive(true);
        ruleSet.setCreatedByUserId(userId);
        ruleSet = repository.save(ruleSet);
        log.info("rule set version created tenant={} docType={} version={} createdBy={}", tenantId, docType,
                nextVersion, userId);
        return ruleSet;
    }

    /**
     * saveAndFlush (not save) on the deactivation, not just the final activation: ux_rule_sets_active
     * is a real Postgres unique index (not deferrable), checked synchronously per statement.
     * Hibernate doesn't guarantee it flushes pending UPDATEs in the order save() was called —
     * a plain save() here let the "activate target" UPDATE reach Postgres before the "deactivate
     * current" one on at least one real run, and for one moment two rows had is_active=true,
     * violating the index (caught live: activating an older version 500'd with
     * "duplicate key value violates unique constraint ux_rule_sets_active" on a tenant that had
     * two versions — not a contrived edge case, the ordinary "roll back to an older version"
     * path). Flushing the deactivation immediately guarantees it's committed before the
     * activation UPDATE is even sent.
     */
    @TenantScoped
    @Transactional
    public ExtractionRuleSet activateVersion(UUID tenantId, String docType, int version) {
        ExtractionRuleSet target = repository.findByTenantIdAndDocTypeAndVersion(tenantId, docType, version)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Rule set version not found"));
        repository.findByTenantIdAndDocTypeAndActiveTrue(tenantId, docType)
                .filter(current -> !current.getId().equals(target.getId()))
                .ifPresent(current -> {
                    current.setActive(false);
                    repository.saveAndFlush(current);
                });
        target.setActive(true);
        ExtractionRuleSet activated = repository.save(target);
        log.info("rule set version activated tenant={} docType={} version={}", tenantId, docType, version);
        return activated;
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public List<ExtractionRuleSet> list(UUID tenantId) {
        return repository.findByTenantIdOrderByDocTypeAscVersionDesc(tenantId);
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public ExtractionRuleSet getActive(UUID tenantId, String docType) {
        return repository.findByTenantIdAndDocTypeAndActiveTrue(tenantId, docType)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No active rule set for doc type " + docType));
    }

    /**
     * Non-throwing counterpart to getActive(), for callers (ExtractionService) that need to
     * catch "no active rule set" as one possible per-document extraction failure among others
     * rather than a request-level 404. A nested @Transactional method throwing marks the
     * whole transaction rollback-only in Spring even if the caller catches the exception —
     * observed in practice as UnexpectedRollbackException at the outer commit — so this
     * avoids the exception path entirely instead of relying on try/catch across bean calls.
     */
    @TenantScoped
    @Transactional(readOnly = true)
    public Optional<ExtractionRuleSet> findActive(UUID tenantId, String docType) {
        return repository.findByTenantIdAndDocTypeAndActiveTrue(tenantId, docType);
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public ExtractionRuleSet getVersion(UUID tenantId, String docType, int version) {
        return repository.findByTenantIdAndDocTypeAndVersion(tenantId, docType, version)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Rule set version not found"));
    }

    /** Non-persisting dry run: interprets sampleText against a definition without touching the DB. */
    public List<InterpretedField> preview(RuleSetDefinition definition, String sampleText) {
        return ruleInterpreter.interpret(sampleText, definition);
    }

    public RuleSetDefinition parseDefinition(ExtractionRuleSet ruleSet) {
        return objectMapper.convertValue(ruleSet.getDefinition(), RuleSetDefinition.class);
    }

    public RuleSetDefinition parseDefaultDefinition(DefaultRuleSet defaultRuleSet) {
        return objectMapper.convertValue(defaultRuleSet.getDefinition(), RuleSetDefinition.class);
    }

    /**
     * Resolution order for actually running extraction: a tenant's own active rule set for
     * this doc type always wins; only when none exists do we fall back to the platform-shipped
     * default for the same doc type (if one exists). This is what turns a bare "no active rule
     * set" failure into working extraction for common types without every tenant having to
     * define rules first — see DefaultRuleSetSeeder for what ships built-in.
     */
    @TenantScoped
    @Transactional(readOnly = true)
    public Optional<RuleSetDefinition> resolveDefinition(UUID tenantId, String docType) {
        Optional<ExtractionRuleSet> custom = repository.findByTenantIdAndDocTypeAndActiveTrue(tenantId, docType);
        if (custom.isPresent()) {
            return Optional.of(parseDefinition(custom.get()));
        }
        return defaultRuleSetRepository.findByDocType(docType).map(this::parseDefaultDefinition);
    }

    /**
     * Merged view for the Rule Sets UI: every doc type that's either tenant-customized or has
     * a shipped default, tagged with which one is actually in effect for that doc type.
     */
    @TenantScoped
    @Transactional(readOnly = true)
    public List<EffectiveRuleSet> listEffective(UUID tenantId) {
        Map<String, ExtractionRuleSet> customByDocType = repository.findByTenantIdAndActiveTrue(tenantId).stream()
                .collect(Collectors.toMap(ExtractionRuleSet::getDocType, rs -> rs));
        Map<String, DefaultRuleSet> defaultsByDocType = defaultRuleSetRepository.findAllByOrderByDocTypeAsc()
                .stream()
                .collect(Collectors.toMap(DefaultRuleSet::getDocType, drs -> drs, (a, b) -> a, LinkedHashMap::new));

        var allDocTypes = new TreeSet<String>();
        allDocTypes.addAll(customByDocType.keySet());
        allDocTypes.addAll(defaultsByDocType.keySet());

        List<EffectiveRuleSet> result = new ArrayList<>();
        for (String docType : allDocTypes) {
            ExtractionRuleSet custom = customByDocType.get(docType);
            if (custom != null) {
                result.add(new EffectiveRuleSet(docType, "CUSTOM", custom.getVersion(), parseDefinition(custom)));
            } else {
                DefaultRuleSet defaultRuleSet = defaultsByDocType.get(docType);
                result.add(new EffectiveRuleSet(docType, "DEFAULT", null, parseDefaultDefinition(defaultRuleSet)));
            }
        }
        return result;
    }
}
