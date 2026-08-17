package com.docstructure.platform.extraction;

/** RuleBasedExtractionStrategy is the only implementation. A new strategy (e.g. an LLM-backed one)
 * is added by implementing this interface as its own @Component, gated behind whatever config
 * property makes sense for it — see docs/DECISIONS.md. */
public interface ExtractionStrategy {
    ExtractionResult extract(ExtractionContext context);
}
