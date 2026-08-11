package com.docstructure.platform.search;

import java.util.Optional;

/**
 * Seam for semantic search. NoOpEmbeddingProvider registers only when no other implementation
 * is present (@ConditionalOnMissingBean) — GeminiEmbeddingProvider takes over once
 * platform.embeddings.enabled=true and an API key is configured. See docs/DECISIONS.md.
 */
public interface EmbeddingProvider {
    boolean isEnabled();

    Optional<float[]> embed(String text);

    /** Recorded alongside a generated vector in extracted_data.embedding_model for provenance. */
    String modelName();
}
