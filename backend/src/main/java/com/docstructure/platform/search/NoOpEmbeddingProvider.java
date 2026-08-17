package com.docstructure.platform.search;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The only EmbeddingProvider bean in this codebase — semantic search is a real, wired seam
 * (SearchService's query path, the pgvector column/index) but nothing generates embeddings
 * today. Adding a real provider later is a new @Component implementing this interface, gated
 * behind whatever config property makes sense for it. See docs/DECISIONS.md.
 */
@Component
public class NoOpEmbeddingProvider implements EmbeddingProvider {
    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public Optional<float[]> embed(String text) {
        return Optional.empty();
    }

    @Override
    public String modelName() {
        return "none";
    }
}
