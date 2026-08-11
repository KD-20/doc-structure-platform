package com.docstructure.platform.rules;

import com.docstructure.platform.common.ApiExceptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dispatches each FieldRule to the FieldExtractor bean named after rule.strategy(), then the
 * Normalizer bean named after rule.normalizer().type() if present. Both maps are populated by
 * Spring's Map<String, T>-of-beans-by-name autowiring — adding a new strategy/normalizer is
 * just a new @Component("NAME") bean, no changes here.
 */
@Component
public class DefaultRuleInterpreter implements RuleInterpreter {

    private final Map<String, FieldExtractor> extractorsByStrategy;
    private final Map<String, Normalizer> normalizersByType;

    public DefaultRuleInterpreter(Map<String, FieldExtractor> extractorsByStrategy,
                                   Map<String, Normalizer> normalizersByType) {
        this.extractorsByStrategy = extractorsByStrategy;
        this.normalizersByType = normalizersByType;
    }

    @Override
    public List<InterpretedField> interpret(String rawText, RuleSetDefinition definition) {
        List<InterpretedField> results = new ArrayList<>();
        for (FieldRule rule : definition.fields()) {
            FieldExtractor extractor = extractorsByStrategy.get(rule.strategy());
            if (extractor == null) {
                throw new ApiExceptions.ValidationException("Unknown extraction strategy: " + rule.strategy());
            }
            FieldExtractionResult extracted = extractor.extract(rawText, rule);
            Object finalValue = extracted.value();
            if (extracted.isFound() && rule.normalizer() != null) {
                Normalizer normalizer = normalizersByType.getOrDefault(rule.normalizer().type(),
                        normalizersByType.get("NOOP"));
                finalValue = normalizer.normalize(finalValue, rule.normalizer());
            }
            results.add(new InterpretedField(rule.name(), finalValue, extracted.confidence(), rule.required()));
        }
        return results;
    }
}
