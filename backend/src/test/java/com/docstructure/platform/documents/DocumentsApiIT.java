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
