package com.docstructure.platform.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Mirrors GeminiEmbeddingProvider's condition on the same property rather than
 * @ConditionalOnMissingBean(EmbeddingProvider.class) — condition evaluation order between plain
 * @Component-scanned beans (as opposed to @Bean methods in an @Configuration class) isn't
 * guaranteed, and @ConditionalOnMissingBean silently excluded both beans in practice. See
 * docs/DECISIONS.md.
 */
@Component
@ConditionalOnProperty(name = "platform.embeddings.enabled", havingValue = "false", matchIfMissing = true)
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
