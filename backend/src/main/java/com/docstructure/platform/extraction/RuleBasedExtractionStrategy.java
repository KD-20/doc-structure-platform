package com.docstructure.platform.extraction;

import com.docstructure.platform.rules.InterpretedField;
import com.docstructure.platform.rules.RuleInterpreter;
import com.docstructure.platform.rules.RuleSetDefinition;
import com.docstructure.platform.rules.RuleSetService;
import com.docstructure.platform.search.ExtractedDataStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component("RULE_BASED")
public class RuleBasedExtractionStrategy implements ExtractionStrategy {

    private final RuleSetService ruleSetService;
    private final RuleInterpreter ruleInterpreter;

    public RuleBasedExtractionStrategy(RuleSetService ruleSetService, RuleInterpreter ruleInterpreter) {
        this.ruleSetService = ruleSetService;
        this.ruleInterpreter = ruleInterpreter;
    }

    @Override
    public ExtractionResult extract(ExtractionContext context) {
        // resolveDefinition (non-throwing), not getActive: this method isn't @Transactional
        // itself, but ExtractionService.performExtraction (the caller) is, and a nested
        // @Transactional method throwing marks that whole transaction rollback-only even when
        // the caller catches it — see RuleSetService#findActive javadoc for the same pitfall.
        // Falls back to a platform-shipped default rule set when the tenant has no active
        // custom one for this doc type — see RuleSetService#resolveDefinition.
        Optional<RuleSetDefinition> definitionOpt =
                ruleSetService.resolveDefinition(context.tenantId(), context.docType());
        if (definitionOpt.isEmpty()) {
            // No rule set at all for this doc type (custom or platform default) — this is no
            // longer a failure. The document just has no fields to structure; its embedding
            // (written unconditionally in ExtractionService#performExtraction, downstream of
            // this call) still gets stored, so it stays findable via semantic/fuzzy search even
            // with zero structured fields. See docs/DECISIONS.md.
            return new ExtractionResult(Map.of(), 0.0, ExtractedDataStatus.UNSTRUCTURED);
        }
        RuleSetDefinition definition = definitionOpt.get();
        List<InterpretedField> interpreted = ruleInterpreter.interpret(context.rawText(), definition);

        Map<String, FieldOutcome> fields = new LinkedHashMap<>();
        boolean anyRequiredMissing = false;
        boolean anyMissing = false;
        double confidenceSum = 0;
        int foundCount = 0;
        for (InterpretedField f : interpreted) {
            fields.put(f.name(), new FieldOutcome(f.value(), f.confidence()));
            if (f.isFound()) {
                confidenceSum += f.confidence();
                foundCount++;
            } else {
                anyMissing = true;
                anyRequiredMissing = anyRequiredMissing || f.required();
            }
        }
        double overallConfidence = foundCount > 0 ? confidenceSum / foundCount : 0.0;
        ExtractedDataStatus status = anyRequiredMissing ? ExtractedDataStatus.NEEDS_REVIEW
                : anyMissing ? ExtractedDataStatus.PARTIAL : ExtractedDataStatus.COMPLETE;
        return new ExtractionResult(fields, overallConfidence, status);
    }
}
