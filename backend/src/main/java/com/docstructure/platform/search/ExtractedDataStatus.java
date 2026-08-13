package com.docstructure.platform.search;

public enum ExtractedDataStatus {
    COMPLETE,
    PARTIAL,
    NEEDS_REVIEW,
    // No rule set (tenant custom or platform default) exists for this document's doc type —
    // no fields were extracted, but its embedding is still written, so it stays findable via
    // semantic/fuzzy search. See RuleBasedExtractionStrategy#extract.
    UNSTRUCTURED
}
