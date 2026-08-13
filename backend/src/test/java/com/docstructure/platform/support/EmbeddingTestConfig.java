package com.docstructure.platform.support;

import com.docstructure.platform.search.EmbeddingProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * @Import this into a test class that needs real (embed-store-search) vector behavior —
 * NoOpEmbeddingProvider is still registered too (its own @ConditionalOnProperty still matches,
 * since platform.embeddings.enabled stays false/unset in tests), but @Primary here wins
 * autowiring. Importing this changes the effective bean set, so Spring Test gives any class that
 * uses it its own application context rather than sharing ApiTestBase's cached one — deliberate
 * and fine: only the one test class that needs this pays that startup cost.
 */
@TestConfiguration
public class EmbeddingTestConfig {

    @Bean
    @Primary
    public EmbeddingProvider testEmbeddingProvider() {
        return new DeterministicEmbeddingProvider();
    }
}
