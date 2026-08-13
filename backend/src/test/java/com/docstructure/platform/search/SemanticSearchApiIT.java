package com.docstructure.platform.search;

import com.docstructure.platform.support.ApiTestBase;
import com.docstructure.platform.support.EmbeddingTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real end-to-end coverage of the embedding pipeline: upload -> async extraction -> embedding
 * written to extracted_data.embedding -> semantic search filtered by
 * SearchQueryBuilder.SEMANTIC_SIMILARITY_THRESHOLD. @Import(EmbeddingTestConfig) swaps in
 * DeterministicEmbeddingProvider (real cosine-distance math against real Postgres/pgvector, just
 * a fake vector source — see its own javadoc) instead of the disabled-by-default
 * NoOpEmbeddingProvider, so this is the one test class where semantic search is actually live.
 */
@Import(EmbeddingTestConfig.class)
class SemanticSearchApiIT extends ApiTestBase {

    private UUID upload(TenantFixture fixture, String filename, String text, String docType) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(text.getBytes()) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        if (docType != null) {
            form.add("docType", docType);
        }
        var headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(fixture.ownerToken());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents",
                HttpMethod.POST, new HttpEntity<>(form, headers), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) res.getBody().get("id"));
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> semanticSearch(TenantFixture fixture, String semanticQuery) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/search")
                .queryParam("semanticQuery", semanticQuery)
                .queryParam("size", 20)
                .build().encode().toUri();
        return rest.exchange(uri, HttpMethod.GET, new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
    }

    /**
     * No docType given (classification path, and no rule set exists for whatever it classifies
     * to) — this is the exact scenario the feature targets: upload auto-triggers extraction
     * (DocumentService#upload's wasClassified branch), RuleBasedExtractionStrategy finds no rule
     * set and returns an UNSTRUCTURED result, and the embedding still gets written.
     */
    @Test
    void unstructuredDocumentWithNoRuleSetOrDocTypeStillGetsEmbeddedAndSearchable() {
        TenantFixture fixture = createTenantWithOwner();
        UUID docId = upload(fixture, "notes.txt",
                "marketing budget campaign social media platforms initiatives", null);
        waitForLatestRunToFinish(fixture.tenantId(), fixture.ownerToken(), docId);

        ResponseEntity<Map> docRes = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId, HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(docRes.getBody().get("status")).isEqualTo("TEXT_EXTRACTED");

        ResponseEntity<List> dataRes = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/extracted-data",
                HttpMethod.GET, new HttpEntity<>(authHeaders(fixture.ownerToken())), List.class);
        List<Map<String, Object>> data = dataRes.getBody();
        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("status")).isEqualTo("UNSTRUCTURED");

        ResponseEntity<Map> searchRes = semanticSearch(fixture, "marketing budget campaign social media");
        assertThat(searchRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) searchRes.getBody().get("semanticSearchAvailable")).isTrue();
        List<Map<String, Object>> items = (List<Map<String, Object>>) searchRes.getBody().get("items");
        assertThat(items).extracting(i -> i.get("documentId")).contains(docId.toString());
    }

    @Test
    void semanticSearchOnlyReturnsResultsAboveSimilarityThreshold() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_semantic_" + uniqueSuffix();

        UUID exactMatch = upload(fixture, "a.txt", "marketing budget campaign social media", docType);
        UUID partialMatch = upload(fixture, "b.txt",
                "marketing budget campaign social media platforms initiatives", docType);
        UUID unrelated = upload(fixture, "c.txt",
                "photosynthesis converts sunlight chemical energy plant chloroplast structures", docType);

        // None of these has a rule set for docType, so upload alone wouldn't auto-trigger (see
        // DocumentService#upload javadoc: explicit docType only auto-triggers on a rule match or
        // an LLM tenant) — trigger and wait for each explicitly.
        for (UUID docId : List.of(exactMatch, partialMatch, unrelated)) {
            ResponseEntity<Map> triggerRes = rest.exchange(
                    baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/extraction-runs",
                    HttpMethod.POST, new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
            UUID runId = UUID.fromString((String) triggerRes.getBody().get("id"));
            waitForTerminalRunStatus(fixture.tenantId(), fixture.ownerToken(), runId);
        }

        ResponseEntity<Map> res = semanticSearch(fixture, "marketing budget campaign social media");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = (List<Map<String, Object>>) res.getBody().get("items");
        Set<String> foundIds = items.stream().map(i -> (String) i.get("documentId")).collect(Collectors.toSet());

        assertThat(foundIds).contains(exactMatch.toString(), partialMatch.toString());
        assertThat(foundIds).doesNotContain(unrelated.toString());
    }

    @Test
    void semanticQueryWithNoResultAboveThresholdReturnsEmptyNotEverything() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_semantic_" + uniqueSuffix();
        UUID docId = upload(fixture, "a.txt", "photosynthesis converts sunlight into chemical energy", docType);
        ResponseEntity<Map> triggerRes = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/extraction-runs",
                HttpMethod.POST, new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        UUID runId = UUID.fromString((String) triggerRes.getBody().get("id"));
        waitForTerminalRunStatus(fixture.tenantId(), fixture.ownerToken(), runId);

        ResponseEntity<Map> res = semanticSearch(fixture, "marketing budget campaign social media platforms");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) res.getBody().get("semanticSearchAvailable")).isTrue();
        List<Map<String, Object>> items = (List<Map<String, Object>>) res.getBody().get("items");
        assertThat(items).isEmpty();
    }
}
