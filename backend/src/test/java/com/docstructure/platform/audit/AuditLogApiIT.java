package com.docstructure.platform.audit;

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

class AuditLogApiIT extends ApiTestBase {

    private ByteArrayResource sampleFile(String filename, String content) {
        return new ByteArrayResource(content.getBytes()) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private UUID upload(TenantFixture fixture, String token, String filename, String content) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", sampleFile(filename, content));
        var headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/documents",
                HttpMethod.POST, new HttpEntity<>(form, headers), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) res.getBody().get("id"));
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> list(TenantFixture fixture, String token, String query) {
        String url = baseUrl() + "/api/tenants/" + fixture.tenantId() + "/audit-log" + (query != null ? query : "");
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), Map.class);
    }

    @Test
    void listReturnsAuditEntryForDocumentUpload() {
        TenantFixture fixture = createTenantWithOwner();
        upload(fixture, fixture.ownerToken(), "a.txt", "hello world");

        ResponseEntity<Map> res = list(fixture, fixture.ownerToken(), null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> content = (List<Map<String, Object>>) res.getBody().get("content");
        assertThat(content).anySatisfy(entry -> {
            assertThat(entry.get("action")).isEqualTo("DOCUMENT_UPLOADED");
            assertThat(entry.get("entityType")).isEqualTo("DOCUMENT");
            assertThat(entry.get("actorUserId")).isNotNull();
        });
    }

    @Test
    void listDeniedForEditor() {
        TenantFixture fixture = createTenantWithOwner();
        String editorToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.EDITOR);

        ResponseEntity<Map> res = list(fixture, editorToken, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void listDeniedForViewer() {
        TenantFixture fixture = createTenantWithOwner();
        String viewerToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.VIEWER);

        ResponseEntity<Map> res = list(fixture, viewerToken, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void listWithoutAuthReturns401() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/audit-log",
                HttpMethod.GET, new HttpEntity<>(jsonHeaders()), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void listWithGarbageTokenReturns401() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = list(fixture, "not-a-real-token", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void crossTenantAdminTokenDeniedNoExistenceLeak() {
        TenantFixture fixtureA = createTenantWithOwner();
        TenantFixture fixtureB = createTenantWithOwner();

        // fixtureB's own admin (owner) token, but pointed at fixtureA's tenantId in the path.
        ResponseEntity<Map> res = list(fixtureA, fixtureB.ownerToken(), null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void listForNonExistentTenantReturns403NotLeakingExistence() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + UUID.randomUUID() + "/audit-log", HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void listFiltersByEntityType() {
        TenantFixture fixture = createTenantWithOwner();
        upload(fixture, fixture.ownerToken(), "a.txt", "hello");
        // TENANT_MEMBER_ROLE_CHANGED needs a second owner so demoting one doesn't hit the
        // "last owner" guard.
        String secondOwnerToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.OWNER);

        ResponseEntity<Map> res = list(fixture, fixture.ownerToken(), "?entityType=DOCUMENT");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> content = (List<Map<String, Object>>) res.getBody().get("content");
        assertThat(content).isNotEmpty();
        assertThat(content).allSatisfy(entry -> assertThat(entry.get("entityType")).isEqualTo("DOCUMENT"));
    }

    @Test
    void listWithUnknownEntityTypeReturnsEmptyNotError() {
        TenantFixture fixture = createTenantWithOwner();
        upload(fixture, fixture.ownerToken(), "a.txt", "hello");

        ResponseEntity<Map> res = list(fixture, fixture.ownerToken(), "?entityType=NO_SUCH_TYPE");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> content = (List<Map<String, Object>>) res.getBody().get("content");
        assertThat(content).isEmpty();
    }

    @Test
    void listWithBlankEntityTypeIsTreatedAsNoFilter() {
        TenantFixture fixture = createTenantWithOwner();
        upload(fixture, fixture.ownerToken(), "a.txt", "hello");

        ResponseEntity<Map> res = list(fixture, fixture.ownerToken(), "?entityType=");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> content = (List<Map<String, Object>>) res.getBody().get("content");
        assertThat(content).isNotEmpty();
    }

    @Test
    void paginationRespectsSizeParam() {
        TenantFixture fixture = createTenantWithOwner();
        upload(fixture, fixture.ownerToken(), "a.txt", "content a");
        upload(fixture, fixture.ownerToken(), "b.txt", "content b");
        upload(fixture, fixture.ownerToken(), "c.txt", "content c");

        ResponseEntity<Map> res = list(fixture, fixture.ownerToken(), "?size=2&page=0");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> content = (List<Map<String, Object>>) res.getBody().get("content");
        assertThat(content).hasSize(2);
        assertThat(((Number) res.getBody().get("totalElements")).longValue()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void paginationBeyondAvailableDataReturnsEmptyNotError() {
        TenantFixture fixture = createTenantWithOwner();
        upload(fixture, fixture.ownerToken(), "a.txt", "content a");

        ResponseEntity<Map> res = list(fixture, fixture.ownerToken(), "?page=999&size=20");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> content = (List<Map<String, Object>>) res.getBody().get("content");
        assertThat(content).isEmpty();
    }

    @Test
    void entriesAreOrderedNewestFirst() {
        TenantFixture fixture = createTenantWithOwner();
        upload(fixture, fixture.ownerToken(), "a.txt", "content a");
        upload(fixture, fixture.ownerToken(), "b.txt", "content b");

        ResponseEntity<Map> res = list(fixture, fixture.ownerToken(), "?size=50");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> content = (List<Map<String, Object>>) res.getBody().get("content");
        List<String> timestamps = content.stream().map(e -> (String) e.get("createdAt")).toList();
        List<String> sortedDesc = timestamps.stream().sorted(java.util.Comparator.reverseOrder()).toList();
        assertThat(timestamps).isEqualTo(sortedDesc);
    }

    @Test
    void roleChangeProducesAuditEntryWithMetadata() {
        TenantFixture fixture = createTenantWithOwner();
        String secondOwnerToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.OWNER);

        // find the second owner's userId via the members list.
        ResponseEntity<List> membersRes = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/members", HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), List.class);
        Map<String, Object> secondOwnerMember = ((List<Map<String, Object>>) membersRes.getBody()).stream()
                .filter(m -> !m.get("email").equals(fixture.ownerEmail()))
                .findFirst().orElseThrow();
        UUID secondOwnerUserId = UUID.fromString((String) secondOwnerMember.get("userId"));

        ResponseEntity<Map> patchRes = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/members/" + secondOwnerUserId + "/role",
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("role", "ADMIN"), authHeaders(fixture.ownerToken())), Map.class);
        assertThat(patchRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> res = list(fixture, fixture.ownerToken(), "?entityType=TENANT_MEMBERSHIP");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> content = (List<Map<String, Object>>) res.getBody().get("content");
        assertThat(content).anySatisfy(entry -> {
            assertThat(entry.get("action")).isEqualTo("TENANT_MEMBER_ROLE_CHANGED");
            assertThat(entry.get("entityId")).isEqualTo(secondOwnerUserId.toString());
        });
    }

    @Test
    void guestLinkCreationIsAuditedButUsageIsNot() {
        TenantFixture fixture = createTenantWithOwner();
        UUID docId = upload(fixture, fixture.ownerToken(), "a.txt", "content a");

        ResponseEntity<Map> createRes = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/guest-links", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "documentIds", List.of(docId.toString()),
                        "expiresAt", java.time.Instant.now().plusSeconds(3600).toString()
                ), authHeaders(fixture.ownerToken())), Map.class);
        assertThat(createRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String guestToken = (String) createRes.getBody().get("token");

        // Use the guest link once - per design, usage itself is not audited (only creation/revocation).
        var guestHeaders = new org.springframework.http.HttpHeaders();
        guestHeaders.set("X-Guest-Token", guestToken);
        rest.exchange(baseUrl() + "/api/guest/documents/" + docId, HttpMethod.GET,
                new HttpEntity<>(guestHeaders), Map.class);

        ResponseEntity<Map> res = list(fixture, fixture.ownerToken(), "?entityType=GUEST_SHARE_LINK&size=50");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> content = (List<Map<String, Object>>) res.getBody().get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("action")).isEqualTo("GUEST_LINK_CREATED");
    }

    @Test
    void metadataIsNeverNullEvenWhenEmpty() {
        TenantFixture fixture = createTenantWithOwner();
        upload(fixture, fixture.ownerToken(), "a.txt", "content a");

        ResponseEntity<Map> res = list(fixture, fixture.ownerToken(), null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> content = (List<Map<String, Object>>) res.getBody().get("content");
        assertThat(content).allSatisfy(entry -> assertThat(entry).containsKey("metadata"));
    }
}
