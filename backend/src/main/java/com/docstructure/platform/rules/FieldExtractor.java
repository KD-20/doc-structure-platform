package com.docstructure.platform.rules;

/** Implementations are Spring beans named after the strategy string they handle (e.g. "ANCHOR_REGEX"), collected by RuleInterpreter via Map<String, FieldExtractor> autowiring. */
public interface FieldExtractor {
    FieldExtractionResult extract(String rawText, FieldRule rule);
}
