package com.docstructure.platform.extraction;

/** RuleBasedExtractionStrategy is the only bean registered in v1; LlmExtractionStrategy implements this same interface but is @ConditionalOnProperty-disabled — see ExtractionStrategyFactory and docs/DECISIONS.md. */
public interface ExtractionStrategy {
    ExtractionResult extract(ExtractionContext context);
}
