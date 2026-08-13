package com.docstructure.platform.rules;

import com.docstructure.platform.auth.MembershipRole;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuleSetsAndExtractionApiIT extends ApiTestBase {

    private static final String SAMPLE_INVOICE_TEXT = """
            ACME Supplies Inc.
            Invoice Number: INV-9001
            Date: 03/14/2026
            Total: $99.00
            """;

    private Map<String, Object> anchorFieldRule(String name, String anchorText, String pattern) {
        return Map.of("name", name, "type", "string", "required", true, "strategy", "ANCHOR_REGEX",
                "params", Map.of("anchorText", anchorText, "searchWindowChars", 80, "pattern", pattern));
    }

    private Map<String, Object> sampleDefinition(String docType) {
        return Map.of("docType", docType, "fields", List.of(
                anchorFieldRule("invoice_number", "Invoice Number:", "([A-Z]{2,4}-\\d{3,8})")));
    }

    private UUID uploadDoc(TenantFixture fixture, String docType) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(SAMPLE_INVOICE_TEXT.getBytes()) {
            @Override
            public String getFilename() {
                return "invoice.txt";
            }
        });
        form.add("docType", docType);
        var headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(fixture.ownerToken());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents",
                HttpMethod.POST, new HttpEntity<>(form, headers), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) res.getBody().get("id"));
    }

    // ---- Rule sets ----

    @Test
    void createVersionSucceedsForAdmin() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_invoice_" + uniqueSuffix();
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType,
                HttpMethod.PUT, new HttpEntity<>(Map.of("definition", sampleDefinition(docType)),
                        authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("version")).isEqualTo(1);
        assertThat(res.getBody().get("active")).isEqualTo(true);
    }

    @Test
    void createSecondVersionIncrementsAndDeactivatesFirst() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_invoice_" + uniqueSuffix();
        rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType, HttpMethod.PUT,
                new HttpEntity<>(Map.of("definition", sampleDefinition(docType)), authHeaders(fixture.ownerToken())), Map.class);

        ResponseEntity<Map> res2 = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType,
                HttpMethod.PUT, new HttpEntity<>(Map.of("definition", sampleDefinition(docType)),
                        authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res2.getBody().get("version")).isEqualTo(2);

        ResponseEntity<Map> v1Res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType + "/versions/1",
                HttpMethod.GET, new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(v1Res.getBody().get("active")).isEqualTo(false);
    }

    @Test
    void createVersionWithMismatchedDocTypeReturns400() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/path_type", HttpMethod.PUT,
                new HttpEntity<>(Map.of("definition", sampleDefinition("different_body_type")),
                        authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createVersionWithNullDefinitionReturns400() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/it_type", HttpMethod.PUT,
                new HttpEntity<>(Map.of(), authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createVersionDeniedForEditorAndViewer() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_invoice_" + uniqueSuffix();
        String editorToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.EDITOR);
        String viewerToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.VIEWER);

        for (String token : List.of(editorToken, viewerToken)) {
            ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType,
                    HttpMethod.PUT, new HttpEntity<>(Map.of("definition", sampleDefinition(docType)), authHeaders(token)),
                    Map.class);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void activateOlderVersionSucceedsForAdmin() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_invoice_" + uniqueSuffix();
        rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType, HttpMethod.PUT,
                new HttpEntity<>(Map.of("definition", sampleDefinition(docType)), authHeaders(fixture.ownerToken())), Map.class);
        rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType, HttpMethod.PUT,
                new HttpEntity<>(Map.of("definition", sampleDefinition(docType)), authHeaders(fixture.ownerToken())), Map.class);

        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType + "/versions/1/activate",
                HttpMethod.POST, new HttpEntity<>(null, authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("version")).isEqualTo(1);
        assertThat(res.getBody().get("active")).isEqualTo(true);
    }

    @Test
    void activateNonExistentVersionReturns404() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_invoice_" + uniqueSuffix();
        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType + "/versions/99/activate",
                HttpMethod.POST, new HttpEntity<>(null, authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getActiveReturns404WhenNoneExists() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/nonexistent_type/active",
                HttpMethod.GET, new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getVersionReturns404ForNonExistentVersion() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_invoice_" + uniqueSuffix();
        rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType, HttpMethod.PUT,
                new HttpEntity<>(Map.of("definition", sampleDefinition(docType)), authHeaders(fixture.ownerToken())), Map.class);
        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType + "/versions/7",
                HttpMethod.GET, new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void effectiveListsBothCustomAndDefaultTypes() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_invoice_" + uniqueSuffix();
        rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType, HttpMethod.PUT,
                new HttpEntity<>(Map.of("definition", sampleDefinition(docType)), authHeaders(fixture.ownerToken())), Map.class);

        ResponseEntity<List> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/effective",
                HttpMethod.GET, new HttpEntity<>(authHeaders(fixture.ownerToken())), List.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> body = res.getBody();
        assertThat(body.stream().anyMatch(e -> docType.equals(e.get("docType")) && "CUSTOM".equals(e.get("source"))))
                .isTrue();
    }

    @Test
    void previewSucceedsForEditorNotJustAdmin() {
        TenantFixture fixture = createTenantWithOwner();
        String editorToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.EDITOR);
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/preview",
                HttpMethod.POST, new HttpEntity<>(Map.of("definition", sampleDefinition("preview_type"),
                        "sampleText", SAMPLE_INVOICE_TEXT), authHeaders(editorToken)), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> fields = (List<Map<String, Object>>) res.getBody().get("fields");
        assertThat(fields).anySatisfy(f -> {
            assertThat(f.get("name")).isEqualTo("invoice_number");
            assertThat(f.get("found")).isEqualTo(true);
            assertThat(f.get("value")).isEqualTo("INV-9001");
        });
    }

    @Test
    void previewDeniedForViewer() {
        TenantFixture fixture = createTenantWithOwner();
        String viewerToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.VIEWER);
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/preview",
                HttpMethod.POST, new HttpEntity<>(Map.of("definition", sampleDefinition("preview_type"),
                        "sampleText", SAMPLE_INVOICE_TEXT), authHeaders(viewerToken)), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void previewWithBlankSampleTextReturns400() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/preview",
                HttpMethod.POST, new HttpEntity<>(Map.of("definition", sampleDefinition("preview_type"), "sampleText", ""),
                        authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void previewWithFieldThatDoesNotMatchReportsNotFoundRatherThanErroring() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/preview",
                HttpMethod.POST, new HttpEntity<>(Map.of("definition",
                        sampleDefinition("preview_type"), "sampleText", "this text has nothing matching at all"),
                        authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> fields = (List<Map<String, Object>>) res.getBody().get("fields");
        assertThat(fields.get(0).get("found")).isEqualTo(false);
    }

    // ---- Bulk re-extraction ----

    @Test
    void savingARuleSetAutomaticallyReextractsExistingDocumentsOfThatType() {
        // Uploaded before any rule set exists for this doc type — lands as unstructured (no
        // fields), same as any no-matching-rule-set document. The point of this test: without
        // ever calling POST .../extraction-runs directly, saving a rule set afterward should be
        // enough to pick this document back up and structure it.
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_auto_reextract_" + uniqueSuffix();
        UUID docId = uploadDoc(fixture, docType);

        rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType, HttpMethod.PUT,
                new HttpEntity<>(Map.of("definition", sampleDefinition(docType)), authHeaders(fixture.ownerToken())),
                Map.class);

        waitForLatestRunToFinish(fixture.tenantId(), fixture.ownerToken(), docId);

        ResponseEntity<List> dataRes = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/extracted-data",
                HttpMethod.GET, new HttpEntity<>(authHeaders(fixture.ownerToken())), List.class);
        List<Map<String, Object>> extracted = dataRes.getBody();
        assertThat(extracted).isNotEmpty();
        Map<String, Object> fields = (Map<String, Object>) extracted.get(0).get("fields");
        Map<String, Object> invoiceNumber = (Map<String, Object>) fields.get("invoice_number");
        assertThat(invoiceNumber.get("value")).isEqualTo("INV-9001");
    }

    @Test
    void activatingAnOlderVersionAlsoReextractsExistingDocuments() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_auto_reextract_activate_" + uniqueSuffix();
        // v1: real matching field. v2: a field that can't possibly match, so a document
        // extracted under v2 would have a null value — proves the reextract after activating v1
        // actually re-ran against v1's definition, not just re-used a stale v2 result.
        rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType, HttpMethod.PUT,
                new HttpEntity<>(Map.of("definition", sampleDefinition(docType)), authHeaders(fixture.ownerToken())),
                Map.class);
        rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType, HttpMethod.PUT,
                new HttpEntity<>(Map.of("definition", Map.of("docType", docType, "fields", List.of(
                        anchorFieldRule("invoice_number", "Nonexistent Label:", "([A-Z]{2,4}-\\d{3,8})")))),
                        authHeaders(fixture.ownerToken())),
                Map.class);

        // Explicit docType, so this does NOT auto-trigger at upload time (see DocumentService's
        // upload javadoc) — this document has genuinely never been extracted until the activate
        // call below reextracts it.
        UUID docId = uploadDoc(fixture, docType);

        rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType + "/versions/1/activate",
                HttpMethod.POST, new HttpEntity<>(null, authHeaders(fixture.ownerToken())), Map.class);
        waitForLatestRunToFinish(fixture.tenantId(), fixture.ownerToken(), docId);

        ResponseEntity<List> dataRes = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/extracted-data",
                HttpMethod.GET, new HttpEntity<>(authHeaders(fixture.ownerToken())), List.class);
        Map<String, Object> latest = (Map<String, Object>) dataRes.getBody().get(0);
        Map<String, Object> fields = (Map<String, Object>) latest.get("fields");
        Map<String, Object> invoiceNumber = (Map<String, Object>) fields.get("invoice_number");
        assertThat(invoiceNumber.get("value")).isEqualTo("INV-9001");
    }

    @Test
    void manualReextractEndpointEnqueuesRunsForEveryDocumentOfThatType() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_manual_reextract_" + uniqueSuffix();
        rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType, HttpMethod.PUT,
                new HttpEntity<>(Map.of("definition", sampleDefinition(docType)), authHeaders(fixture.ownerToken())),
                Map.class);
        // Both explicit docType, so neither auto-triggers at upload time — both sit unextracted
        // until the manual reextract call below.
        UUID docA = uploadDoc(fixture, docType);
        UUID docB = uploadDoc(fixture, docType);

        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType + "/reextract",
                HttpMethod.POST, new HttpEntity<>(null, authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) res.getBody().get("documentsEnqueued")).intValue()).isEqualTo(2);
        assertThat(((Number) res.getBody().get("documentsSkipped")).intValue()).isEqualTo(0);

        // See ApiTestBase#waitForTerminalRunStatus's javadoc: any test that triggers extraction
        // must wait for a terminal status before returning, or @AfterEach cleanup can race a
        // still-in-flight background worker.
        waitForLatestRunToFinish(fixture.tenantId(), fixture.ownerToken(), docA);
        waitForLatestRunToFinish(fixture.tenantId(), fixture.ownerToken(), docB);
    }

    @Test
    void manualReextractDeniedForViewer() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_manual_reextract_denied_" + uniqueSuffix();
        String viewerToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.VIEWER);
        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType + "/reextract",
                HttpMethod.POST, new HttpEntity<>(null, authHeaders(viewerToken)), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- Extraction ----

    @Test
    void triggerExtractionSucceedsWithActiveRuleSet() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_invoice_" + uniqueSuffix();
        rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType, HttpMethod.PUT,
                new HttpEntity<>(Map.of("definition", sampleDefinition(docType)), authHeaders(fixture.ownerToken())), Map.class);
        UUID docId = uploadDoc(fixture, docType);

        // Async now (see ExtractionService#enqueueExtraction): the POST itself only guarantees
        // a run was created, not that it finished — 202 + PENDING immediately, poll for the
        // terminal outcome.
        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/extraction-runs",
                HttpMethod.POST, new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(res.getBody().get("status")).isEqualTo("PENDING");
        UUID runId = UUID.fromString((String) res.getBody().get("id"));

        Map<String, Object> finished = waitForTerminalRunStatus(fixture.tenantId(), fixture.ownerToken(), runId);
        assertThat(finished.get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    void triggerExtractionWithNoActiveRuleSetSucceedsAsUnstructured() {
        // RuleBasedExtractionStrategy no longer fails when there's no active rule set for this
        // doc type (custom or platform default) — it returns an UNSTRUCTURED result (no fields)
        // instead, so the run still SUCCEEDS and the document still gets embedded for
        // semantic/fuzzy search. See RuleBasedExtractionStrategy's own javadoc.
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_no_ruleset_" + uniqueSuffix();
        UUID docId = uploadDoc(fixture, docType);

        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/extraction-runs",
                HttpMethod.POST, new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(res.getBody().get("status")).isEqualTo("PENDING");
        UUID runId = UUID.fromString((String) res.getBody().get("id"));

        Map<String, Object> finished = waitForTerminalRunStatus(fixture.tenantId(), fixture.ownerToken(), runId);
        assertThat(finished.get("status")).isEqualTo("SUCCEEDED");

        ResponseEntity<Map> docRes = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId, HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        // Not STRUCTURED: that specifically means fields were extracted, which didn't happen here.
        assertThat(docRes.getBody().get("status")).isEqualTo("TEXT_EXTRACTED");

        ResponseEntity<List> dataRes = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/extracted-data",
                HttpMethod.GET, new HttpEntity<>(authHeaders(fixture.ownerToken())), List.class);
        List<Map<String, Object>> data = dataRes.getBody();
        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("status")).isEqualTo("UNSTRUCTURED");
        assertThat((Map<String, Object>) data.get(0).get("fields")).isEmpty();
    }

    @Test
    void triggerExtractionForNonExistentDocumentReturns404() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + UUID.randomUUID() + "/extraction-runs",
                HttpMethod.POST, new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void triggerExtractionDeniedForViewer() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_invoice_" + uniqueSuffix();
        UUID docId = uploadDoc(fixture, docType);
        String viewerToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.VIEWER);

        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/extraction-runs",
                HttpMethod.POST, new HttpEntity<>(authHeaders(viewerToken)), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void listRunsReturnsEmptyForDocumentNeverExtracted() {
        TenantFixture fixture = createTenantWithOwner();
        UUID docId = uploadDoc(fixture, "it_no_ruleset_" + uniqueSuffix());
        ResponseEntity<List> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/extraction-runs",
                HttpMethod.GET, new HttpEntity<>(authHeaders(fixture.ownerToken())), List.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEmpty();
    }

    @Test
    void getRunReturns404ForNonExistentRun() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/extraction-runs/" + UUID.randomUUID(),
                HttpMethod.GET, new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void extractedDataReturnsEmptyForDocumentNeverExtracted() {
        TenantFixture fixture = createTenantWithOwner();
        UUID docId = uploadDoc(fixture, "it_no_ruleset_" + uniqueSuffix());
        ResponseEntity<List> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/extracted-data",
                HttpMethod.GET, new HttpEntity<>(authHeaders(fixture.ownerToken())), List.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEmpty();
    }

    @Test
    void extractedDataReflectsRealExtractionResult() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_invoice_" + uniqueSuffix();
        rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType, HttpMethod.PUT,
                new HttpEntity<>(Map.of("definition", sampleDefinition(docType)), authHeaders(fixture.ownerToken())), Map.class);
        UUID docId = uploadDoc(fixture, docType);
        ResponseEntity<Map> triggerRes = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/extraction-runs",
                HttpMethod.POST, new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        UUID runId = UUID.fromString((String) triggerRes.getBody().get("id"));
        waitForTerminalRunStatus(fixture.tenantId(), fixture.ownerToken(), runId);

        ResponseEntity<List> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/extracted-data",
                HttpMethod.GET, new HttpEntity<>(authHeaders(fixture.ownerToken())), List.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> body = res.getBody();
        assertThat(body).hasSize(1);
        Map<String, Object> fields = (Map<String, Object>) body.get(0).get("fields");
        Map<String, Object> invoiceNumberField = (Map<String, Object>) fields.get("invoice_number");
        assertThat(invoiceNumberField.get("value")).isEqualTo("INV-9001");
    }
}
