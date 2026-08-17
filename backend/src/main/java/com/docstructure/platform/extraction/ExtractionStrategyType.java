package com.docstructure.platform.extraction;

/** Tagged on every ExtractionRun for observability (logs, metrics). RULE_BASED is the only strategy
 * in this codebase today; a future strategy is added by implementing ExtractionStrategy, adding an
 * enum value here, and wiring selection wherever a new strategy needs to be chosen (see docs/DECISIONS.md). */
public enum ExtractionStrategyType {
    RULE_BASED
}
