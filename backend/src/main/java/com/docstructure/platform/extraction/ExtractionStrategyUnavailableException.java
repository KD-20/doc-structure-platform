package com.docstructure.platform.extraction;

/** Thrown when a tenant is configured for a strategy whose bean isn't registered (e.g. extractionStrategy=LLM while platform.extraction.llm.enabled=false) — fails loud rather than silently falling back to a different strategy. */
public class ExtractionStrategyUnavailableException extends RuntimeException {
    public ExtractionStrategyUnavailableException(String message) {
        super(message);
    }
}
