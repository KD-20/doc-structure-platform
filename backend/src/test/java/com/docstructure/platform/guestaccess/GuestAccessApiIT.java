package com.docstructure.platform.guestaccess;

import com.docstructure.platform.auth.MembershipRole;
import com.docstructure.platform.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GuestAccessApiIT extends ApiTestBase {

    private UUID uploadDoc(TenantFixture fixture, String content) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(content.getBytes()) {
            @Override
            public String getFilename() {
                return "doc.txt";
            }
        });
        var headers = new HttpHeaders();
        headers.setBearerAuth(fixture.ownerToken());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents",
                HttpMethod.POST, new HttpEntity<>(form, headers), Map.class);
        return UUID.fromString((String) res.getBody().get("id"));
    }

    private ResponseEntity<Map> createLink(TenantFixture fixture, String token, List<UUID> documentIds, Integer maxUses) {
        Map<String, Object> req = new java.util.HashMap<>();
        req.put("documentIds", documentIds.stream().map(UUID::toString).toList());
        req.put("expiresAt", Instant.now().plus(7, ChronoUnit.DAYS).toString());
        req.put("maxUses", maxUses);
        return rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/guest-links", HttpMethod.POST,
                new HttpEntity<>(req, authHeaders(token)), Map.class);
    }

    private HttpHeaders guestTokenHeaders(String guestToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Guest-Token", guestToken);
        return headers;
    }

    // ---- Guest link admin API ----

    @Test
    void createGuestLinkSucceedsForAdmin() {
        TenantFixture fixture = createTenantWithOwner();
        UUID docId = uploadDoc(fixture, "shareable content");
        ResponseEntity<Map> res = createLink(fixture, fixture.ownerToken(), List.of(docId), null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("token")).isNotNull();
    }

    @Test
    void createGuestLinkDeniedForEditorAndViewer() {
        TenantFixture fixture = createTenantWithOwner();
        UUID docId = uploadDoc(fixture, "shareable content");
        String editorToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.EDITOR);
        String viewerToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.VIEWER);

        for (String token : List.of(editorToken, viewerToken)) {
            ResponseEntity<Map> res = createLink(fixture, token, List.of(docId), null);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void createGuestLinkWithEmptyDocumentIdsReturns400() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = createLink(fixture, fixture.ownerToken(), List.of(), null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createGuestLinkWithPastExpiryReturns400() {
        TenantFixture fixture = createTenantWithOwner();
        UUID docId = uploadDoc(fixture, "shareable content");
        Map<String, Object> req = new java.util.HashMap<>();
        req.put("documentIds", List.of(docId.toString()));
        req.put("expiresAt", Instant.now().minus(1, ChronoUnit.DAYS).toString());
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/guest-links",
                HttpMethod.POST, new HttpEntity<>(req, authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listGuestLinksSucceedsForAdminDeniedForOthers() {
        TenantFixture fixture = createTenantWithOwner();
        UUID docId = uploadDoc(fixture, "shareable content");
        createLink(fixture, fixture.ownerToken(), List.of(docId), null);

        ResponseEntity<List> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/guest-links",
                HttpMethod.GET, new HttpEntity<>(authHeaders(fixture.ownerToken())), List.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).hasSize(1);

        String viewerToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.VIEWER);
        ResponseEntity<Map> deniedRes = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/guest-links",
                HttpMethod.GET, new HttpEntity<>(authHeaders(viewerToken)), Map.class);
        assertThat(deniedRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void revokeSucceedsForAdmin() {
        TenantFixture fixture = createTenantWithOwner();
        UUID docId = uploadDoc(fixture, "shareable content");
        ResponseEntity<Map> createRes = createLink(fixture, fixture.ownerToken(), List.of(docId), null);
        UUID linkId = UUID.fromString((String) createRes.getBody().get("id"));

        ResponseEntity<Void> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/guest-links/" + linkId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Void.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void revokeNonExistentLinkReturns404() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/guest-links/" + UUID.randomUUID(), HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- Guest-facing access ----

    @Test
    void guestCanAccessDocumentWithinScope() {
        TenantFixture fixture = createTenantWithOwner();
        UUID docId = uploadDoc(fixture, "guest visible content");
        ResponseEntity<Map> createRes = createLink(fixture, fixture.ownerToken(), List.of(docId), null);
        String guestToken = (String) createRes.getBody().get("token");

        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/guest/documents/" + docId, HttpMethod.GET,
                new HttpEntity<>(guestTokenHeaders(guestToken)), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("documentId")).isEqualTo(docId.toString());
    }

    @Test
    void guestCannotAccessDocumentOutsideScope() {
        TenantFixture fixture = createTenantWithOwner();
        UUID scopedDocId = uploadDoc(fixture, "in scope");
        UUID otherDocId = uploadDoc(fixture, "not in scope");
        ResponseEntity<Map> createRes = createLink(fixture, fixture.ownerToken(), List.of(scopedDocId), null);
        String guestToken = (String) createRes.getBody().get("token");

        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/guest/documents/" + otherDocId, HttpMethod.GET,
                new HttpEntity<>(guestTokenHeaders(guestToken)), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void revokedTokenIsRejected() {
        TenantFixture fixture = createTenantWithOwner();
        UUID docId = uploadDoc(fixture, "content");
        ResponseEntity<Map> createRes = createLink(fixture, fixture.ownerToken(), List.of(docId), null);
        String guestToken = (String) createRes.getBody().get("token");
        UUID linkId = UUID.fromString((String) createRes.getBody().get("id"));

        rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/guest-links/" + linkId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Void.class);

        // Not "insufficient privilege" (403) — a revoked token means GuestAuthFilter never
        // authenticates the request at all, same class of outcome as a missing/garbage JWT
        // elsewhere in this app (see AuthApiIT's garbage-token case), which is 401.
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/guest/documents/" + docId, HttpMethod.GET,
                new HttpEntity<>(guestTokenHeaders(guestToken)), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredTokenIsRejected() {
        TenantFixture fixture = createTenantWithOwner();
        UUID docId = uploadDoc(fixture, "content");
        ResponseEntity<Map> createRes = createLink(fixture, fixture.ownerToken(), List.of(docId), null);
        String guestToken = (String) createRes.getBody().get("token");
        UUID linkId = UUID.fromString((String) createRes.getBody().get("id"));
        backdateGuestLinkExpiry(fixture.tenantId(), linkId, Instant.now().minus(1, ChronoUnit.DAYS));

        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/guest/documents/" + docId, HttpMethod.GET,
                new HttpEntity<>(guestTokenHeaders(guestToken)), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void garbageTokenIsRejected() {
        TenantFixture fixture = createTenantWithOwner();
        UUID docId = uploadDoc(fixture, "content");
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/guest/documents/" + docId, HttpMethod.GET,
                new HttpEntity<>(guestTokenHeaders("totally-fake-token-" + uniqueSuffix())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void missingTokenIsRejected() {
        TenantFixture fixture = createTenantWithOwner();
        UUID docId = uploadDoc(fixture, "content");
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/guest/documents/" + docId, HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void maxUsesExceededRejectsFurtherAccess() {
        TenantFixture fixture = createTenantWithOwner();
        UUID docId = uploadDoc(fixture, "limited use content");
        ResponseEntity<Map> createRes = createLink(fixture, fixture.ownerToken(), List.of(docId), 1);
        String guestToken = (String) createRes.getBody().get("token");

        ResponseEntity<Map> first = rest.exchange(baseUrl() + "/api/guest/documents/" + docId, HttpMethod.GET,
                new HttpEntity<>(guestTokenHeaders(guestToken)), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> second = rest.exchange(baseUrl() + "/api/guest/documents/" + docId, HttpMethod.GET,
                new HttpEntity<>(guestTokenHeaders(guestToken)), Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void guestSearchOnlyFindsDocumentsWithinScope() {
        TenantFixture fixture = createTenantWithOwner();
        UUID scopedDocId = uploadDoc(fixture, "findable marker GXSCOPE1111");
        UUID otherDocId = uploadDoc(fixture, "findable marker GXSCOPE1111 too");
        ResponseEntity<Map> createRes = createLink(fixture, fixture.ownerToken(), List.of(scopedDocId), null);
        String guestToken = (String) createRes.getBody().get("token");

        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/guest/search?q=GXSCOPE1111", HttpMethod.GET,
                new HttpEntity<>(guestTokenHeaders(guestToken)), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = (List<Map<String, Object>>) res.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("documentId")).isEqualTo(scopedDocId.toString());
    }
}
