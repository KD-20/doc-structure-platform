package com.docstructure.platform.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Calls Google's Gemini embeddings API (embedContent). outputDimensionality is pinned to 1536
 * to exactly match the pre-existing extracted_data.embedding VECTOR(1536) column — no schema
 * migration needed to enable this. See docs/DECISIONS.md.
 */
@Component
@ConditionalOnProperty(name = "platform.embeddings.enabled", havingValue = "true")
public class GeminiEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingProvider.class);
    private static final int OUTPUT_DIMENSIONALITY = 1536;
    private static final int MAX_TEXT_CHARS = 8_000;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final String baseUrl;

    public GeminiEmbeddingProvider(ObjectMapper objectMapper,
                                    @Value("${platform.embeddings.api-key}") String apiKey,
                                    @Value("${platform.embeddings.model}") String model,
                                    @Value("${platform.embeddings.base-url}") String baseUrl) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
    }

    @Override
    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public Optional<float[]> embed(String text) {
        if (!isEnabled() || text == null || text.isBlank()) {
            return Optional.empty();
        }
        String trimmed = text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text;
        try {
            Map<String, Object> requestBody = Map.of(
                    "content", Map.of("parts", List.of(Map.of("text", trimmed))),
                    "outputDimensionality", OUTPUT_DIMENSIONALITY);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/models/" + model + ":embedContent?key=" + apiKey))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Gemini embedding request failed (HTTP {}): {}", response.statusCode(), truncate(response.body()));
                return Optional.empty();
            }
            JsonNode values = objectMapper.readTree(response.body()).at("/embedding/values");
            if (!values.isArray() || values.isEmpty()) {
                log.warn("Gemini embedding response had no values: {}", truncate(response.body()));
                return Optional.empty();
            }
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = (float) values.get(i).asDouble();
            }
            return Optional.of(vector);
        } catch (IOException e) {
            log.warn("Gemini embedding request failed: {}", e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private String truncate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
