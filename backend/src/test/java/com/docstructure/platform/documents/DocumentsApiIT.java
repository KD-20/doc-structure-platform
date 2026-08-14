package com.docstructure.platform.documents;

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

class DocumentsApiIT extends ApiTestBase {

    private ByteArrayResource sampleFile(String filename, String content) {
        return new ByteArrayResource(content.getBytes()) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private ResponseEntity<Map> upload(TenantFixture fixture, String token, String filename, String content, String docType) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", sampleFile(filename, content));
        if (docType != null) {
            form.add("docType", docType);
        }
        HttpHeadersMultipart headers = new HttpHeadersMultipart(token);
        return rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents", HttpMethod.POST,
                new HttpEntity<>(form, headers.headers), Map.class);
    }

    private UUID uploadReturningId(TenantFixture fixture, String filename, String content, String docType) {
        ResponseEntity<Map> res = upload(fixture, fixture.ownerToken(), filename, content, docType);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) res.getBody().get("id"));
    }

    /** Small wrapper just to keep multipart Content-Type + bearer auth construction in one place. */
    private static class HttpHeadersMultipart {
        final org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();

        HttpHeadersMultipart(String token) {
            headers.setBearerAuth(token);
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        }
    }

    @Test
    void uploadSucceedsForEditor() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = upload(fixture, fixture.ownerToken(), "sample.txt", "Invoice Number: INV-1\nTotal: $10.00", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("filename")).isEqualTo("sample.txt");
    }

    /**
     * Regression test for a real live bug: content-based auto-classification (an earlier
     * DocTypeClassifier#classify tier, since removed) labeled a plain "Commands" text file as
     * "resume" and marked it STRUCTURED, purely because it contained a digit sequence
     * ("Commands" happened to include something the shipped resume rule set's very loose phone
     * regex matched) — that rule set's fields are all optional, so the "all required fields
     * found" bonus applied unconditionally, pushing a single coincidental match over the
     * confidence threshold. No doc type selected must never guess a specific business type from
     * content anymore — see DocumentService#upload's javadoc.
     */
    @Test
    void uploadWithNoDocTypeNeverGuessesABusinessTypeFromContent() {
        TenantFixture fixture = createTenantWithOwner();
        // No anchor words for any shipped default rule set (invoice/receipt/resume/contract/
        // payslip) — just a phone-number-shaped digit run, exactly the kind of coincidental
        // match that used to be enough on its own to win "resume".
        ResponseEntity<Map> res = upload(fixture, fixture.ownerToken(), "Commands.txt",
                "Build 4.10.2026, run with flags --port 555-123-4567 --retries 3", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("docType")).isNotEqualTo("resume");
        assertThat(res.getBody().get("docType")).isEqualTo("text_document");
        assertThat(res.getBody().get("status")).isEqualTo("TEXT_EXTRACTED");
    }

    /**
     * Regression coverage for the UI's live-status feature: a document can genuinely finish
     * processing with no structured fields at all (RuleBasedExtractionStrategy's UNSTRUCTURED
     * fallback), and the frontend needs latestExtractionRunStatus (not just status, which stays
     * TEXT_EXTRACTED either way) to tell that apart from "never even attempted" — see
     * DocumentSummaryResponse's own javadoc.
     */
    @Test
    void latestExtractionRunStatusDistinguishesNeverRunFromRanUnstructured() {
        TenantFixture fixture = createTenantWithOwner();

        UUID neverRunDocId = uploadReturningId(fixture, "explicit.txt", "just some content",
                "it_never_run_" + uniqueSuffix());
        ResponseEntity<Map> neverRunRes = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + neverRunDocId, HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(neverRunRes.getBody().get("status")).isEqualTo("TEXT_EXTRACTED");
        assertThat(neverRunRes.getBody().get("latestExtractionRunStatus")).isNull();

        // No explicit docType (classification path) with no matching rule set — auto-triggers,
        // runs, and lands on UNSTRUCTURED (see DocumentService#upload's javadoc).
        UUID autoRunDocId = uploadReturningId(fixture, "no_type.txt", "just some other content", null);
        waitForLatestRunToFinish(fixture.tenantId(), fixture.ownerToken(), autoRunDocId);
        ResponseEntity<Map> autoRunRes = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + autoRunDocId, HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(autoRunRes.getBody().get("status")).isEqualTo("TEXT_EXTRACTED");
        assertThat(autoRunRes.getBody().get("latestExtractionRunStatus")).isEqualTo("SUCCEEDED");

        // Same distinction must survive in the list endpoint too (batched query, not just get()).
        ResponseEntity<Map> listRes = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents?size=50", HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        List<Map<String, Object>> content = (List<Map<String, Object>>) listRes.getBody().get("content");
        Map<String, Object> neverRunInList = content.stream()
                .filter(d -> d.get("id").equals(neverRunDocId.toString())).findFirst().orElseThrow();
        Map<String, Object> autoRunInList = content.stream()
                .filter(d -> d.get("id").equals(autoRunDocId.toString())).findFirst().orElseThrow();
        assertThat(neverRunInList.get("latestExtractionRunStatus")).isNull();
        assertThat(autoRunInList.get("latestExtractionRunStatus")).isEqualTo("SUCCEEDED");
    }

    /**
     * Regression test for a real gap: DocumentService's own auto-trigger javadoc always claimed
     * an explicit docType auto-triggers "when a rule set actually resolves for it", but the code
     * only ever checked wasClassified/LLM — an explicit docType matching a real, working rule
     * set (e.g. uploading as "resume" when a resume rule set already exists) silently sat at
     * "Structured — Not yet run" until someone clicked the manual retrigger. Confirmed live
     * before this was fixed. Distinct from the "no matching rule set" case covered by
     * latestExtractionRunStatusDistinguishesNeverRunFromRanUnstructured, which must still NOT
     * auto-trigger.
     */
    @Test
    void uploadWithExplicitDocTypeMatchingAnActiveRuleSetAutoTriggers() {
        TenantFixture fixture = createTenantWithOwner();
        String docType = "it_explicit_autotrigger_" + uniqueSuffix();
        rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/rule-sets/" + docType, HttpMethod.PUT,
                new HttpEntity<>(Map.of("definition", Map.of("docType", docType, "fields", List.of(
                        Map.of("name", "marker", "type", "string", "required", false, "strategy", "REGEX_GLOBAL",
                                "params", Map.of("pattern", "(MARK-\\d+)"))))),
                        authHeaders(fixture.ownerToken())), Map.class);

        UUID docId = uploadReturningId(fixture, "explicit_with_rule_set.txt", "Reference MARK-4321 here.", docType);
        waitForLatestRunToFinish(fixture.tenantId(), fixture.ownerToken(), docId);

        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId, HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getBody().get("status")).isEqualTo("STRUCTURED");
        assertThat(res.getBody().get("latestExtractionRunStatus")).isEqualTo("SUCCEEDED");
    }

    @Test
    void uploadWithExplicitDocTypeIsRespected() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = upload(fixture, fixture.ownerToken(), "sample.txt", "hello world", "my_custom_type");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("docType")).isEqualTo("my_custom_type");
    }

    @Test
    void uploadDeniedForViewer() {
        TenantFixture fixture = createTenantWithOwner();
        String viewerToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.VIEWER);
        ResponseEntity<Map> res = upload(fixture, viewerToken, "sample.txt", "hello", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void uploadWithEmptyFileReturns400() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = upload(fixture, fixture.ownerToken(), "empty.txt", "", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void uploadWithoutFilePartReturns400() {
        TenantFixture fixture = createTenantWithOwner();
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("docType", "whatever");
        var headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(fixture.ownerToken());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents",
                HttpMethod.POST, new HttpEntity<>(form, headers), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void uploadWithoutAuthReturns401() {
        TenantFixture fixture = createTenantWithOwner();
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", sampleFile("x.txt", "hello"));
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents",
                HttpMethod.POST, new HttpEntity<>(form, headers), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void listReturnsUploadedDocumentsForViewer() {
        TenantFixture fixture = createTenantWithOwner();
        upload(fixture, fixture.ownerToken(), "a.txt", "content a", null);
        upload(fixture, fixture.ownerToken(), "b.txt", "content b", null);
        String viewerToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.VIEWER);

        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents",
                HttpMethod.GET, new HttpEntity<>(authHeaders(viewerToken)), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((List<?>) res.getBody().get("content"))).hasSize(2);
    }

    @Test
    void listFiltersByDocType() {
        TenantFixture fixture = createTenantWithOwner();
        upload(fixture, fixture.ownerToken(), "a.txt", "content a", "type_a");
        upload(fixture, fixture.ownerToken(), "b.txt", "content b", "type_b");

        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents?docType=type_a", HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> content = (List<Map<String, Object>>) res.getBody().get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("docType")).isEqualTo("type_a");
    }

    @Test
    void getReturns404ForNonExistentDocument() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + UUID.randomUUID(), HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getFromWrongTenantReturns404NotLeakingCrossTenantExistence() {
        TenantFixture fixtureA = createTenantWithOwner();
        TenantFixture fixtureB = createTenantWithOwner();
        ResponseEntity<Map> uploadRes = upload(fixtureA, fixtureA.ownerToken(), "a.txt", "content", null);
        UUID docId = UUID.fromString((String) uploadRes.getBody().get("id"));

        // fixtureB's own token, scoped to tenant B, used against B's own URL path but
        // referencing A's document id — RLS means it's invisible, indistinguishable from
        // never having existed.
        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixtureB.tenantId() + "/documents/" + docId, HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixtureB.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rawTextReturnsExtractedContent() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> uploadRes = upload(fixture, fixture.ownerToken(), "a.txt", "hello raw text world", null);
        UUID docId = UUID.fromString((String) uploadRes.getBody().get("id"));

        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/raw-text", HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) res.getBody().get("rawText")).contains("hello raw text world");
    }

    @Test
    void downloadReturnsOriginalBytes() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> uploadRes = upload(fixture, fixture.ownerToken(), "a.txt", "download me please", null);
        UUID docId = UUID.fromString((String) uploadRes.getBody().get("id"));

        ResponseEntity<byte[]> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/download", HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), byte[].class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(res.getBody())).isEqualTo("download me please");
    }

    @Test
    void downloadForNonExistentDocumentReturns404() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<byte[]> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + UUID.randomUUID() + "/download",
                HttpMethod.GET, new HttpEntity<>(authHeaders(fixture.ownerToken())), byte[].class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateDocTypeSucceedsForEditor() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> uploadRes = upload(fixture, fixture.ownerToken(), "a.txt", "content", "old_type");
        UUID docId = UUID.fromString((String) uploadRes.getBody().get("id"));

        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/doc-type", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("docType", "new_type"), authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("docType")).isEqualTo("new_type");

        // updateDocType re-triggers extraction (async) whenever there's raw text, regardless of
        // whether "new_type" actually has a matching rule set — wait for it so @AfterEach's
        // cleanup doesn't race the still-in-flight background run.
        waitForLatestRunToFinish(fixture.tenantId(), fixture.ownerToken(), docId);
    }

    @Test
    void updateDocTypeWithBlankValueReturns400() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> uploadRes = upload(fixture, fixture.ownerToken(), "a.txt", "content", null);
        UUID docId = UUID.fromString((String) uploadRes.getBody().get("id"));

        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/doc-type", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("docType", ""), authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateDocTypeForNonExistentDocumentReturns404() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + UUID.randomUUID() + "/doc-type",
                HttpMethod.PATCH, new HttpEntity<>(Map.of("docType", "x"), authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateDocTypeDeniedForViewer() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> uploadRes = upload(fixture, fixture.ownerToken(), "a.txt", "content", null);
        UUID docId = UUID.fromString((String) uploadRes.getBody().get("id"));
        String viewerToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.VIEWER);

        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId + "/doc-type", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("docType", "new_type"), authHeaders(viewerToken)), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void deleteSucceedsForAdmin() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> uploadRes = upload(fixture, fixture.ownerToken(), "a.txt", "content", null);
        UUID docId = UUID.fromString((String) uploadRes.getBody().get("id"));

        ResponseEntity<Void> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Void.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> getRes = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId, HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(getRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteDeniedForEditor() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> uploadRes = upload(fixture, fixture.ownerToken(), "a.txt", "content", null);
        UUID docId = UUID.fromString((String) uploadRes.getBody().get("id"));
        String editorToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.EDITOR);

        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + docId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(editorToken)), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void deleteForNonExistentDocumentReturns404() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents/" + UUID.randomUUID(), HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
