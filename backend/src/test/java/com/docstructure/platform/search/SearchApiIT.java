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

        // Extraction is async (see ExtractionService#enqueueExtraction) — the explicit trigger
        // below is redundant with upload's own auto-trigger (the rule set is already active by
        // the time it uploads) but harmless; either way, callers here need extracted_data to
        // actually exist before they search/filter on it, so wait for a terminal run status.
        ResponseEntity<Map> triggerRes = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/extraction-runs",
                HttpMethod.POST, new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        UUID runId = UUID.fromString((String) triggerRes.getBody().get("id"));
        waitForTerminalRunStatus(fixture.tenantId(), fixture.ownerToken(), runId);
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
    void shortNumericQueryTermDoesNotPrefixMatchUnrelatedNumbers() {
        // Regression test: plainto_tsquery('9 sheets 14 tables') stems to '9' & 'sheet' & '14' &
        // 'tabl', and TsQueryExpr.PREFIX_OR_MATCH used to tag every lexeme with a ':*' prefix
        // wildcard unconditionally — so the bare digit "9" became '9':*, which matches ANY
        // number token starting with 9. Reproduced live against a real document whose only
        // connection to the query was an ID number starting with 9; it had no "sheet"/"table"
        // text anywhere. Fixed by only wildcarding lexemes of 3+ characters (see TsQueryExpr's
        // javadoc); short lexemes now require an exact match instead.
        TenantFixture fixture = createTenantWithOwner();
        String text = "VID: 9170 9130 2215 8483 issued at counter forty two";
        var headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(fixture.ownerToken());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> form = multipart(Map.of("file", new ByteArrayResource(text.getBytes()) {
            @Override
            public String getFilename() {
                return "id-card.txt";
            }
        }));
        ResponseEntity<Map> uploadRes = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents",
                HttpMethod.POST, new HttpEntity<>(form, headers), Map.class);
        String docId = (String) uploadRes.getBody().get("id");

        ResponseEntity<Map> res = searchRaw(fixture, Map.of("q", "9 sheets 14 tables"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = (List<Map<String, Object>>) res.getBody().get("items");
        assertThat(items).extracting(item -> item.get("documentId")).doesNotContain(docId);
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
    void anyFieldFilterMatchesRegardlessOfWhichFieldHoldsIt() {
        // "*" (ANY_FIELD sentinel, see SearchQueryBuilder) — the caller doesn't have to know or
        // guess which extracted field a value lives in, just that some field on the document has
        // it. Two fields on purpose (invoice_number and total_amount) so this can't accidentally
        // pass by only ever having one field to search anyway.
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_anyfield_" + uniqueSuffix();
        rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType, HttpMethod.PUT,
                new HttpEntity<>(Map.of("definition", Map.of("docType", docType, "fields", List.of(
                        anchorFieldRule("invoice_number", "Invoice Number:", "([A-Z]{2,4}-\\d{3,8})"),
                        anchorFieldRule("total_amount", "Total:", "\\$?([\\d.]+)")))),
                        authHeaders(fixture.ownerToken())), Map.class);

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource("Invoice Number: ZZAF-0007\nTotal: $12.50".getBytes()) {
            @Override
            public String getFilename() {
                return "doc.txt";
            }
        });
        form.add("docType", docType);
        var headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(fixture.ownerToken());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> uploadRes = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents",
                HttpMethod.POST, new HttpEntity<>(form, headers), Map.class);
        UUID docId = UUID.fromString((String) uploadRes.getBody().get("id"));
        ResponseEntity<Map> triggerRes = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/extraction-runs",
                HttpMethod.POST, new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        waitForTerminalRunStatus(fixture.tenantId(), fixture.ownerToken(),
                UUID.fromString((String) triggerRes.getBody().get("id")));

        String filters = "[{\"field\":\"*\",\"op\":\"eq\",\"value\":\"ZZAF-0007\"}]";
        ResponseEntity<Map> res = searchRaw(fixture, Map.of("filters", filters, "docType", docType));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) res.getBody().get("totalElements")).intValue()).isEqualTo(1);
    }

    @Test
    void anyFieldFilterContainsAndRangeOperatorsWork() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_anyfield_range_" + uniqueSuffix();
        uploadAndStructure(fixture, docType, "Marker here.\nTotal: $88.00");

        String containsFilters = "[{\"field\":\"*\",\"op\":\"contains\",\"value\":\"88\"}]";
        ResponseEntity<Map> containsRes = searchRaw(fixture, Map.of("filters", containsFilters, "docType", docType));
        assertThat(((Number) containsRes.getBody().get("totalElements")).intValue()).isEqualTo(1);

        String rangeFilters = "[{\"field\":\"*\",\"op\":\"gte\",\"value\":\"50\"}]";
        ResponseEntity<Map> rangeRes = searchRaw(fixture, Map.of("filters", rangeFilters, "docType", docType));
        assertThat(((Number) rangeRes.getBody().get("totalElements")).intValue()).isEqualTo(1);
    }

    @Test
    void fuzzyFilterToleratesTyposThatContainsCannotMatch() {
        // "Kurukshtra" (typo, missing the second 'e') is not a literal substring of the real
        // extracted value "Kurukshetra" — contains/eq would both miss it — but word_similarity
        // scores it ~0.64 (verified in psql), comfortably above the 0.4 threshold
        // TsQueryExpr.TRIGRAM_THRESHOLD already uses for fuzzy full-text matching, so "fuzzy"
        // should still find it against the real, imperfectly-typed city field.
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_fuzzy_field_" + uniqueSuffix();
        rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType, HttpMethod.PUT,
                new HttpEntity<>(Map.of("definition", Map.of("docType", docType, "fields", List.of(
                        anchorFieldRule("city", "City:", "([A-Za-z]+)")))),
                        authHeaders(fixture.ownerToken())), Map.class);

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource("City: Kurukshetra".getBytes()) {
            @Override
            public String getFilename() {
                return "doc.txt";
            }
        });
        form.add("docType", docType);
        var headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(fixture.ownerToken());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> uploadRes = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents",
                HttpMethod.POST, new HttpEntity<>(form, headers), Map.class);
        UUID docId = UUID.fromString((String) uploadRes.getBody().get("id"));
        ResponseEntity<Map> triggerRes = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/extraction-runs",
                HttpMethod.POST, new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        waitForTerminalRunStatus(fixture.tenantId(), fixture.ownerToken(),
                UUID.fromString((String) triggerRes.getBody().get("id")));

        String fuzzyFilters = "[{\"field\":\"city\",\"op\":\"fuzzy\",\"value\":\"Kurukshtra\"}]";
        ResponseEntity<Map> fuzzyRes = searchRaw(fixture, Map.of("filters", fuzzyFilters, "docType", docType));
        assertThat(((Number) fuzzyRes.getBody().get("totalElements")).intValue()).isEqualTo(1);

        String containsMiss = "[{\"field\":\"city\",\"op\":\"contains\",\"value\":\"Kurukshtra\"}]";
        ResponseEntity<Map> containsMissRes = searchRaw(fixture, Map.of("filters", containsMiss, "docType", docType));
        assertThat(containsMissRes.getBody().get("totalElements")).isEqualTo(0);
    }

    @Test
    void fuzzyFilterFindsNothingBelowSimilarityThreshold() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_fuzzy_field_miss_" + uniqueSuffix();
        uploadAndStructure(fixture, docType, "Marker here.\nTotal: $50.00");

        String filters = "[{\"field\":\"total_amount\",\"op\":\"fuzzy\",\"value\":\"completely_unrelated_xyz\"}]";
        ResponseEntity<Map> res = searchRaw(fixture, Map.of("filters", filters, "docType", docType));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("totalElements")).isEqualTo(0);
    }

    @Test
    void anyFieldFilterFindsNothingWhenNoFieldMatches() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_anyfield_miss_" + uniqueSuffix();
        uploadAndStructure(fixture, docType, "Marker here.\nTotal: $88.00");

        String filters = "[{\"field\":\"*\",\"op\":\"eq\",\"value\":\"" + uniqueSuffix() + "_definitely_absent\"}]";
        ResponseEntity<Map> res = searchRaw(fixture, Map.of("filters", filters, "docType", docType));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("totalElements")).isEqualTo(0);
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
