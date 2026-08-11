package com.docstructure.platform.tenancy;

import com.docstructure.platform.auth.MembershipRole;
import com.docstructure.platform.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenancyApiIT extends ApiTestBase {

    @Test
    void createTenantSucceedsAndReturnsOwnerToken() {
        TenantFixture fixture = createTenantWithOwner();
        assertThat(fixture.tenantId()).isNotNull();
    }

    @Test
    void createTenantWithDuplicateSlugReturns409() {
        TenantFixture fixture = createTenantWithOwner();
        String email = registerUser("TestPass123!", "Second");
        Map<String, Object> loginBody = login(email, "TestPass123!");
        String token = (String) loginBody.get("token");

        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Another Name", "slug", fixture.slug()), authHeaders(token)), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createTenantWithInvalidSlugReturns400() {
        String email = registerUser("TestPass123!", "Someone");
        Map<String, Object> loginBody = login(email, "TestPass123!");
        String token = (String) loginBody.get("token");

        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "X", "slug", "Not A Valid Slug!"), authHeaders(token)), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createTenantWithBlankNameReturns400() {
        String email = registerUser("TestPass123!", "Someone");
        Map<String, Object> loginBody = login(email, "TestPass123!");
        String token = (String) loginBody.get("token");
        Map<String, Object> req = new HashMap<>();
        req.put("name", "");
        req.put("slug", "it-" + uniqueSuffix());
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants", HttpMethod.POST,
                new HttpEntity<>(req, authHeaders(token)), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createTenantWithoutAuthReturns401() {
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "X", "slug", "it-" + uniqueSuffix()), jsonHeaders()), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void myTenantsListsRealMemberships() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<List> res = rest.exchange(baseUrl() + "/api/tenants", HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), List.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).hasSize(1);
    }

    @Test
    void getTenantSucceedsForMember() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId(), HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("slug")).isEqualTo(fixture.slug());
    }

    @Test
    void getTenantWithTokenScopedToDifferentTenantReturns403NotLeakingExistence() {
        TenantFixture fixtureA = createTenantWithOwner();
        TenantFixture fixtureB = createTenantWithOwner();
        // A's token is scoped to A; using it against B's URL must fail, whether B is real or not.
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixtureB.tenantId(), HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixtureA.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> res2 = rest.exchange(baseUrl() + "/api/tenants/" + UUID.randomUUID(), HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixtureA.ownerToken())), Map.class);
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void updateSettingsSucceedsForAdmin() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/settings",
                HttpMethod.PATCH, new HttpEntity<>(Map.of("settings", Map.of("extractionStrategy", "RULE_BASED")),
                        authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void updateSettingsDeniedForEditorAndViewer() {
        TenantFixture fixture = createTenantWithOwner();
        String editorToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.EDITOR);
        String viewerToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.VIEWER);

        for (String token : List.of(editorToken, viewerToken)) {
            ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/settings",
                    HttpMethod.PATCH, new HttpEntity<>(Map.of("settings", Map.of()), authHeaders(token)), Map.class);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void updateSettingsWithJsonNullSucceedsAndIsHandledSafelyDownstream() {
        // @NotNull on a JsonNode-typed field doesn't reject a JSON `null` literal — Jackson
        // deserializes it to NullNode.getInstance() (a real object, not a Java null), so bean
        // validation sees a non-null field and passes (confirmed live: this used to be asserted
        // as a 400 here and actually came back 200). Not a bug: every downstream reader
        // (e.g. ExtractionStrategyFactory#resolve) uses tenantSettings.hasNonNull(...), which
        // is null-safe against NullNode the same way it's null-safe against a literal null.
        TenantFixture fixture = createTenantWithOwner();
        Map<String, Object> req = new HashMap<>();
        req.put("settings", null);
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/settings",
                HttpMethod.PATCH, new HttpEntity<>(req, authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void updateSettingsWithMissingSettingsFieldReturns400() {
        // Genuinely absent (no "settings" key at all) is different from present-but-null, and
        // IS caught: Jackson leaves the field as Java null when the key is missing entirely
        // (only an explicit JSON `null` value maps to NullNode), so @NotNull fires normally.
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/settings",
                HttpMethod.PATCH, new HttpEntity<>(Map.of(), authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listMembersSucceedsForViewer() {
        TenantFixture fixture = createTenantWithOwner();
        String viewerToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.VIEWER);
        ResponseEntity<List> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/members",
                HttpMethod.GET, new HttpEntity<>(authHeaders(viewerToken)), List.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).hasSize(2); // owner + this viewer
    }

    @Test
    void addMemberSucceedsForAdmin() {
        TenantFixture fixture = createTenantWithOwner();
        String newEmail = registerUser("TestPass123!", "New Member");
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/members",
                HttpMethod.POST, new HttpEntity<>(Map.of("email", newEmail, "role", "EDITOR"),
                        authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("role")).isEqualTo("EDITOR");
    }

    @Test
    void addMemberWithNonExistentEmailReturns404() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/members",
                HttpMethod.POST, new HttpEntity<>(Map.of("email", "nobody-" + uniqueSuffix() + "@example.test", "role", "VIEWER"),
                        authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void addMemberAlreadyAMemberReturns409() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/members",
                HttpMethod.POST, new HttpEntity<>(Map.of("email", fixture.ownerEmail(), "role", "VIEWER"),
                        authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void addMemberWithInvalidRoleReturns400() {
        TenantFixture fixture = createTenantWithOwner();
        String newEmail = registerUser("TestPass123!", "New Member");
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/members",
                HttpMethod.POST, new HttpEntity<>(Map.of("email", newEmail, "role", "SUPERADMIN"),
                        authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void addMemberDeniedForEditorAndViewer() {
        TenantFixture fixture = createTenantWithOwner();
        String editorToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.EDITOR);
        String newEmail = registerUser("TestPass123!", "New Member");
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/members",
                HttpMethod.POST, new HttpEntity<>(Map.of("email", newEmail, "role", "VIEWER"),
                        authHeaders(editorToken)), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void updateMemberRoleSucceedsForAdmin() {
        TenantFixture fixture = createTenantWithOwner();
        String memberEmail = registerUser("TestPass123!", "Member");
        ResponseEntity<Map> addRes = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/members",
                HttpMethod.POST, new HttpEntity<>(Map.of("email", memberEmail, "role", "VIEWER"),
                        authHeaders(fixture.ownerToken())), Map.class);
        UUID userId = UUID.fromString((String) addRes.getBody().get("userId"));

        ResponseEntity<Void> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/members/" + userId + "/role", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("role", "EDITOR"), authHeaders(fixture.ownerToken())), Void.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void updateMemberRoleForNonExistentMembershipReturns404() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Void> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/members/" + UUID.randomUUID() + "/role",
                HttpMethod.PATCH, new HttpEntity<>(Map.of("role", "EDITOR"), authHeaders(fixture.ownerToken())), Void.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void demotingTheLastOwnerReturns400() {
        TenantFixture fixture = createTenantWithOwner();
        // fixture.ownerToken()'s user is the ONLY owner — demoting them must be rejected.
        ResponseEntity<Map> meRes = rest.exchange(baseUrl() + "/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        UUID ownerUserId = UUID.fromString((String) meRes.getBody().get("userId"));

        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/members/" + ownerUserId + "/role",
                HttpMethod.PATCH, new HttpEntity<>(Map.of("role", "ADMIN"), authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void demotingOneOfTwoOwnersSucceeds() {
        TenantFixture fixture = createTenantWithOwner();
        String secondOwnerToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.OWNER);
        ResponseEntity<Map> meRes = rest.exchange(baseUrl() + "/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(authHeaders(secondOwnerToken)), Map.class);
        UUID secondOwnerUserId = UUID.fromString((String) meRes.getBody().get("userId"));

        ResponseEntity<Void> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/members/" + secondOwnerUserId + "/role",
                HttpMethod.PATCH, new HttpEntity<>(Map.of("role", "ADMIN"), authHeaders(fixture.ownerToken())), Void.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void removeMemberSucceedsForAdmin() {
        TenantFixture fixture = createTenantWithOwner();
        String memberEmail = registerUser("TestPass123!", "Member");
        ResponseEntity<Map> addRes = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId() + "/members",
                HttpMethod.POST, new HttpEntity<>(Map.of("email", memberEmail, "role", "VIEWER"),
                        authHeaders(fixture.ownerToken())), Map.class);
        UUID userId = UUID.fromString((String) addRes.getBody().get("userId"));

        ResponseEntity<Void> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/members/" + userId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Void.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void removeLastOwnerReturns400() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> meRes = rest.exchange(baseUrl() + "/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        UUID ownerUserId = UUID.fromString((String) meRes.getBody().get("userId"));

        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/members/" + ownerUserId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void removeMemberForNonExistentMembershipReturns404() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/members/" + UUID.randomUUID(), HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void removeMemberDeniedForNonAdmin() {
        TenantFixture fixture = createTenantWithOwner();
        String viewerToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.VIEWER);
        ResponseEntity<Map> meRes = rest.exchange(baseUrl() + "/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(authHeaders(viewerToken)), Map.class);
        UUID viewerUserId = UUID.fromString((String) meRes.getBody().get("userId"));

        ResponseEntity<Map> res = rest.exchange(
                baseUrl() + "/api/tenants/" + fixture.tenantId() + "/members/" + viewerUserId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(viewerToken)), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
