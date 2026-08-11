package com.docstructure.platform.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Resolves which ExtractionStrategy bean handles a tenant, keyed by tenant.settings.extractionStrategy
 * (falling back to platform.extraction.default-strategy). strategiesByName is populated by Spring's
 * Map<String, T>-of-beans-by-name autowiring: RuleBasedExtractionStrategy is always present
 * ("RULE_BASED"), LlmExtractionStrategy only when platform.extraction.llm.enabled=true ("LLM").
 * A tenant configured for a strategy whose bean isn't registered fails loudly rather than
 * silently falling back — see ExtractionStrategyUnavailableException.
 */
@Component
public class ExtractionStrategyFactory {

    private final Map<String, ExtractionStrategy> strategiesByName;
    private final String defaultStrategy;

    public ExtractionStrategyFactory(Map<String, ExtractionStrategy> strategiesByName,
                                      @Value("${platform.extraction.default-strategy}") String defaultStrategy) {
        this.strategiesByName = strategiesByName;
        this.defaultStrategy = defaultStrategy;
    }

    public ExtractionStrategy resolve(JsonNode tenantSettings) {
        String requested = tenantSettings != null && tenantSettings.hasNonNull("extractionStrategy")
                ? tenantSettings.get("extractionStrategy").asText()
                : defaultStrategy;
        ExtractionStrategy strategy = strategiesByName.get(requested);
        if (strategy == null) {
            throw new ExtractionStrategyUnavailableException(
                    "Extraction strategy '" + requested + "' is not available. "
                            + "If this is LLM, set platform.extraction.llm.enabled=true and provide an implementation.");
        }
        return strategy;
    }
}
