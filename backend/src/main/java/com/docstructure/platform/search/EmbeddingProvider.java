package com.docstructure.platform.search;

import java.util.Optional;

/**
 * Seam for semantic search. NoOpEmbeddingProvider is the only bean in this codebase today; a
 * real provider is added by implementing this interface as its own @Component (typically
 * @Primary or gated behind a config property so it takes over from NoOp). See docs/DECISIONS.md.
 */
public interface EmbeddingProvider {
    boolean isEnabled();

    Optional<float[]> embed(String text);

    /** Recorded alongside a generated vector in extracted_data.embedding_model for provenance. */
    String modelName();
}
