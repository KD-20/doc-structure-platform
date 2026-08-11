package com.docstructure.platform.extraction;

import com.docstructure.platform.search.ExtractedDataStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * True zero-config extraction: no rule set required for this doc type at all — the model
 * reads the raw text and decides for itself which fields matter and what their values are,
 * instead of a human pre-defining field names/anchors (see RuleBasedExtractionStrategy). Only
 * registered as a bean when platform.extraction.llm.enabled=true (an API key alone doesn't
 * turn this on — see application.yml), and only used by a tenant that's explicitly opted in
 * via tenant.settings.extractionStrategy="LLM" (ExtractionStrategyFactory); the platform-wide
 * default stays RULE_BASED, so existing tenants with tuned rule sets aren't affected.
 *
 * Uses Groq's OpenAI-compatible Chat Completions API directly via java.net.http rather than
 * pulling in an SDK — one HTTP call, no extra dependency.
 */
@Component("LLM")
@ConditionalOnProperty(name = "platform.extraction.llm.enabled", havingValue = "true")
public class LlmExtractionStrategy implements ExtractionStrategy {

    /** Keeps prompt size (and therefore token cost/latency) bounded regardless of document length. */
    private static final int MAX_TEXT_CHARS = 12_000;

    private static final String SYSTEM_PROMPT = """
            You extract structured data from documents. Given raw document text, identify the \
            most important fields a person would want as structured data (for an invoice: \
            invoice number, date, total; for a resume: name, email, phone; and so on for \
            whatever this document actually is). Respond with ONLY a JSON object, no prose, no \
            markdown code fences, in exactly this shape:
            {"fields": {"<field_name>": {"value": "<string>", "confidence": <0.0-1.0 number>}}}
            Use lowercase_snake_case field names. Include 3 to 8 fields, whichever are most \
            relevant to this specific document. If a field genuinely isn't present in the text, \
            omit it rather than guessing a value.""";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final String baseUrl;

    public LlmExtractionStrategy(ObjectMapper objectMapper,
                                  @Value("${platform.extraction.llm.api-key}") String apiKey,
                                  @Value("${platform.extraction.llm.model}") String model,
                                  @Value("${platform.extraction.llm.base-url}") String baseUrl) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
    }

    @Override
    public ExtractionResult extract(ExtractionContext context) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "LLM extraction is enabled but no API key is configured (set GROQ_API_KEY).");
        }
        String text = context.rawText();
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
        }

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content",
                                "Document type hint: " + context.docType() + "\n\nDocument text:\n" + text)),
                "response_format", Map.of("type", "json_object"),
                "temperature", 0.1);

        JsonNode fieldsNode;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "LLM request failed (HTTP " + response.statusCode() + "): " + truncate(response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.at("/choices/0/message/content").asText();
            fieldsNode = objectMapper.readTree(content).path("fields");
        } catch (IOException e) {
            throw new IllegalStateException("LLM extraction request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM extraction request interrupted", e);
        }

        Map<String, FieldOutcome> fields = new LinkedHashMap<>();
        double confidenceSum = 0;
        int count = 0;
        for (Iterator<String> names = fieldsNode.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            JsonNode fieldNode = fieldsNode.get(name);
            Object value = fieldNode.hasNonNull("value") ? fieldNode.get("value").asText() : null;
            double confidence = fieldNode.hasNonNull("confidence") ? fieldNode.get("confidence").asDouble() : 0.7;
            fields.put(name, new FieldOutcome(value, confidence));
            if (value != null) {
                confidenceSum += confidence;
                count++;
            }
        }

        double overallConfidence = count > 0 ? confidenceSum / count : 0.0;
        ExtractedDataStatus status = fields.isEmpty() ? ExtractedDataStatus.NEEDS_REVIEW : ExtractedDataStatus.COMPLETE;
        return new ExtractionResult(fields, overallConfidence, status);
    }

    private String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
