package com.docstructure.platform.support;

import com.docstructure.platform.search.EmbeddingProvider;

import java.util.Locale;
import java.util.Optional;

/**
 * A real, deterministic (no network, no API key) stand-in for a real cloud embedding provider —
 * bag-of-words hashed into a 1536-dim vector (same dimensionality as the real extracted_data.embedding
 * column, so no schema mismatch), L2-normalized. This is not a mock: it's genuinely exercised
 * end-to-end against real Postgres/pgvector, including the actual cosine-distance query in
 * SearchQueryBuilder — only the vector *source* is fake, so tests can verify the full
 * embed-store-search-threshold pipeline without a real embeddings API key.
 * <p>
 * Similarity is intuitive by construction: two texts sharing most of their words land close
 * together (cosine similarity well above SEMANTIC_SIMILARITY_THRESHOLD); texts sharing no words
 * are exactly orthogonal (similarity 0, since hashed word buckets don't overlap) — enough to
 * construct reliable above/below-threshold test cases without needing real semantic understanding.
 */
public class DeterministicEmbeddingProvider implements EmbeddingProvider {

    private static final int DIMENSIONS = 1536;

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public Optional<float[]> embed(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        double[] vector = new double[DIMENSIONS];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.isBlank()) {
                continue;
            }
            int bucket = Math.floorMod(word.hashCode(), DIMENSIONS);
            vector[bucket] += 1.0;
        }
        double norm = 0.0;
        for (double v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        float[] result = new float[DIMENSIONS];
        if (norm > 0) {
            for (int i = 0; i < DIMENSIONS; i++) {
                result[i] = (float) (vector[i] / norm);
            }
        }
        return Optional.of(result);
    }

    @Override
    public String modelName() {
        return "deterministic-test-fake";
    }
}
