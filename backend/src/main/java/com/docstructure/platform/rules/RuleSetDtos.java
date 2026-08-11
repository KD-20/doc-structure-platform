package com.docstructure.platform.rules;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response records for the rule-sets API. Package-private: only RuleSetController needs these. */
record RuleSetResponse(UUID id, String docType, int version, RuleSetDefinition definition, boolean active,
                        Instant createdAt) {
    static RuleSetResponse from(ExtractionRuleSet rs, RuleSetDefinition definition) {
        return new RuleSetResponse(rs.getId(), rs.getDocType(), rs.getVersion(), definition, rs.isActive(),
                rs.getCreatedAt());
    }
}

record CreateRuleSetVersionRequest(@NotNull RuleSetDefinition definition) {
}

record PreviewRequest(@NotNull RuleSetDefinition definition, @NotBlank String sampleText) {
}

record PreviewResponse(List<InterpretedField> fields) {
}

/** source is "CUSTOM" (tenant has an active override) or "DEFAULT" (falling back to the platform-shipped template); activeVersion is only set for CUSTOM. */
record EffectiveRuleSet(String docType, String source, Integer activeVersion, RuleSetDefinition definition) {
}
