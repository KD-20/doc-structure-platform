package com.docstructure.platform.search;

import com.docstructure.platform.support.ApiTestBase;
import org.junit.jupiter.api.Test;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SearchApiIT extends ApiTestBase {

    private Map<String, Object> anchorFieldRule(String name, String anchorText, String pattern) {
        return Map.of("name", name, "type", "string", "required", true, "strategy", "ANCHOR_REGEX",
                "params", Map.of("anchorText", anchorText, "searchWindowChars", 80, "pattern", pattern));
    }

    private UUID uploadAndStructure(TenantFixture fixture, String docType, String text) {
        rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType, HttpMethod.PUT,
                new HttpEntity<>(Map.of("definition", Map.of("docType", docType, "fields",
                        List.of(anchorFieldRule("total_amount", "Total:", "\\$?([\\d.]+)")))),
                        authHeaders(fixture.ownerToken())), Map.class);

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(text.getBytes()) {
            @Override
            public String getFilename() {
                return "doc.txt";
            }
        });
        form.add("docType", docType);
        var headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(fixture.ownerToken());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents",
                HttpMethod.POST, new HttpEntity<>(form, headers), Map.class);
        UUID docId = UUID.fromString((String) res.getBody().get("id"));

        rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/extraction-runs",
                HttpMethod.POST, new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        return docId;
    }

    private ResponseEntity<Map> searchRaw(TenantFixture fixture, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/search");
        params.forEach(builder::queryParam);
        URI uri = builder.build().encode().toUri();
        return rest.exchange(uri, HttpMethod.GET, new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
    }

    @Test
    void fullTextSearchFindsRealMatch() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_search_" + uniqueSuffix();
        uploadAndStructure(fixture, docType, "Unique marker XKCD1234 appears here.\nTotal: $50.00");

        ResponseEntity<Map> res = searchRaw(fixture, Map.of("q", "XKCD1234"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) res.getBody().get("totalElements")).intValue()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void fullTextSearchWithNoMatchReturnsEmptyNotError() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = searchRaw(fixture, Map.of("q", "zzz_definitely_not_present_" + uniqueSuffix()));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("totalElements")).isEqualTo(0);
    }

    @Test
    void searchWithoutAuthReturns401() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/search",
                HttpMethod.GET, new HttpEntity<>(jsonHeaders()), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void searchScopedToWrongTenantReturns403() {
        TenantFixture fixtureA = createTenantWithOwner();
        TenantFixture fixtureB = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixtureB.tenantId() + "/search",
                HttpMethod.GET, new HttpEntity<>(authHeaders(fixtureA.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void docTypeFilterNarrowsResults() {
        TenantFixture fixture = createTenantWithOwner();
        String typeA = "it_type_a_" + uniqueSuffix();
        String typeB = "it_type_b_" + uniqueSuffix();
        uploadAndStructure(fixture, typeA, "Shared marker HQZZ9999 here.\nTotal: $10.00");
        uploadAndStructure(fixture, typeB, "Shared marker HQZZ9999 here.\nTotal: $20.00");

        ResponseEntity<Map> res = searchRaw(fixture, Map.of("q", "HQZZ9999", "docType", typeA));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = (List<Map<String, Object>>) res.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("docType")).isEqualTo(typeA);
    }

    @Test
    void structuredFilterEqMatchesRealExtractedField() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_filter_" + uniqueSuffix();
        uploadAndStructure(fixture, docType, "Marker QFIL0001.\nTotal: $77.00");

        String filters = "[{\"field\":\"total_amount\",\"op\":\"eq\",\"value\":\"77.00\"}]";
        ResponseEntity<Map> res = searchRaw(fixture, Map.of("filters", filters, "docType", docType));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) res.getBody().get("totalElements")).intValue()).isEqualTo(1);
    }

    @Test
    void structuredFilterRangeGtMatchesAndExcludesCorrectly() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_filter_" + uniqueSuffix();
        uploadAndStructure(fixture, docType, "Marker QFIL0002.\nTotal: $77.00");

        ResponseEntity<Map> aboveRes = searchRaw(fixture, Map.of(
                "filters", "[{\"field\":\"total_amount\",\"op\":\"gt\",\"value\":\"50\"}]", "docType", docType));
        assertThat(aboveRes.getBody().get("totalElements")).isEqualTo(1);

        ResponseEntity<Map> belowRes = searchRaw(fixture, Map.of(
                "filters", "[{\"field\":\"total_amount\",\"op\":\"gt\",\"value\":\"100\"}]", "docType", docType));
        assertThat(belowRes.getBody().get("totalElements")).isEqualTo(0);
    }

    @Test
    void structuredFilterRangeWithNonNumericValueReturns400() {
        TenantFixture fixture = createTenantWithOwner();
        String filters = "[{\"field\":\"total_amount\",\"op\":\"gt\",\"value\":\"not-a-number\"}]";
        ResponseEntity<Map> res = searchRaw(fixture, Map.of("filters", filters));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void malformedFiltersJsonReturns400() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = searchRaw(fixture, Map.of("filters", "{not valid json"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void filterOnUnknownFieldReturnsEmptyNotError() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_filter_" + uniqueSuffix();
        uploadAndStructure(fixture, docType, "Marker QFIL0003.\nTotal: $10.00");
        String filters = "[{\"field\":\"this_field_does_not_exist\",\"op\":\"eq\",\"value\":\"x\"}]";
        ResponseEntity<Map> res = searchRaw(fixture, Map.of("filters", filters, "docType", docType));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("totalElements")).isEqualTo(0);
    }

    @Test
    void semanticQueryWithoutEmbeddingsFallsBackToLiteralTextInsteadOfReturningEverything() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_search_" + uniqueSuffix();
        uploadAndStructure(fixture, docType, "Unrelated content only.\nTotal: $5.00");

        ResponseEntity<Map> res = searchRaw(fixture, Map.of("semanticQuery", "zzz_nonexistent_" + uniqueSuffix()));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("semanticQueryProvided")).isEqualTo(true);
        assertThat(res.getBody().get("semanticSearchAvailable")).isEqualTo(false);
        // The core regression this session fixed: must NOT return every document in the tenant.
        assertThat(res.getBody().get("totalElements")).isEqualTo(0);
    }

    @Test
    void docTypesReturnsDistinctRealTypes() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_search_" + uniqueSuffix();
        uploadAndStructure(fixture, docType, "content one.\nTotal: $1.00");
        uploadAndStructure(fixture, docType, "content two.\nTotal: $2.00");

        ResponseEntity<List> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/search/doc-types",
                HttpMethod.GET, new HttpEntity<>(authHeaders(fixture.ownerToken())), List.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains(docType);
        assertThat(((List<?>) res.getBody()).stream().filter(docType::equals).count()).isEqualTo(1);
    }

    @Test
    void fieldsReturnsRealExtractedFieldNamesPaginated() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_search_" + uniqueSuffix();
        uploadAndStructure(fixture, docType, "content.\nTotal: $9.00");

        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/search/fields?docType=" + docType + "&page=0&size=5",
                HttpMethod.GET, new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> content = (List<String>) res.getBody().get("content");
        assertThat(content).contains("total_amount");
    }

    @Test
    void fieldsWithQueryFilterNarrowsAutocomplete() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_search_" + uniqueSuffix();
        uploadAndStructure(fixture, docType, "content.\nTotal: $9.00");

        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/search/fields?docType=" + docType + "&q=total&page=0&size=5",
                HttpMethod.GET, new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> content = (List<String>) res.getBody().get("content");
        assertThat(content).allSatisfy(name -> assertThat(name).containsIgnoringCase("total"));
    }

    @Test
    void searchPaginationBeyondAvailableDataReturnsEmptyNotError() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = searchRaw(fixture, Map.of("page", "999", "size", "20"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((List<?>) res.getBody().get("items"))).isEmpty();
    }
}
