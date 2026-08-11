package com.docstructure.platform.auth;

import com.docstructure.platform.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthApiIT extends ApiTestBase {

    @Test
    void registerThenLoginSucceeds() {
        String password = "TestPass123!";
        String email = registerUser(password, "Real User");
        Map<String, Object> body = login(email, password);
        assertThat(body.get("token")).isNotNull();
        assertThat((java.util.List<?>) body.get("tenants")).isEmpty();
    }

    @Test
    void registerWithDuplicateEmailReturns409() {
        String password = "TestPass123!";
        String email = registerUser(password, "First");
        ResponseEntity<Map> res = rest.postForEntity(baseUrl() + "/api/auth/register",
                new HttpEntity<>(Map.of("email", email, "password", password, "fullName", "Second"), jsonHeaders()),
                Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void registerWithBlankEmailReturns400() {
        Map<String, Object> req = new HashMap<>();
        req.put("email", "");
        req.put("password", "TestPass123!");
        req.put("fullName", "Someone");
        ResponseEntity<Map> res = rest.postForEntity(baseUrl() + "/api/auth/register",
                new HttpEntity<>(req, jsonHeaders()), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void registerWithMalformedEmailReturns400() {
        Map<String, Object> req = Map.of("email", "not-an-email", "password", "TestPass123!", "fullName", "Someone");
        ResponseEntity<Map> res = rest.postForEntity(baseUrl() + "/api/auth/register",
                new HttpEntity<>(req, jsonHeaders()), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void registerWithShortPasswordReturns400() {
        Map<String, Object> req = Map.of("email", "it-" + uniqueSuffix() + "@example.test",
                "password", "short", "fullName", "Someone");
        ResponseEntity<Map> res = rest.postForEntity(baseUrl() + "/api/auth/register",
                new HttpEntity<>(req, jsonHeaders()), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void registerWithNullFieldsReturns400() {
        Map<String, Object> req = new HashMap<>();
        req.put("email", null);
        req.put("password", null);
        req.put("fullName", null);
        ResponseEntity<Map> res = rest.postForEntity(baseUrl() + "/api/auth/register",
                new HttpEntity<>(req, jsonHeaders()), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void registerWithMissingBodyReturns400() {
        ResponseEntity<Map> res = rest.postForEntity(baseUrl() + "/api/auth/register",
                new HttpEntity<>("{}", jsonHeaders()), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void loginWithWrongPasswordReturns401() {
        String email = registerUser("TestPass123!", "Someone");
        ResponseEntity<Map> res = rest.postForEntity(baseUrl() + "/api/auth/login",
                new HttpEntity<>(Map.of("email", email, "password", "WrongPassword!"), jsonHeaders()), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginWithNonExistentEmailReturns401NotLeakingExistence() {
        ResponseEntity<Map> res = rest.postForEntity(baseUrl() + "/api/auth/login",
                new HttpEntity<>(Map.of("email", "nobody-" + uniqueSuffix() + "@example.test", "password", "TestPass123!"),
                        jsonHeaders()),
                Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginWithBlankPasswordReturns400() {
        String email = registerUser("TestPass123!", "Someone");
        Map<String, Object> req = new HashMap<>();
        req.put("email", email);
        req.put("password", "");
        ResponseEntity<Map> res = rest.postForEntity(baseUrl() + "/api/auth/login",
                new HttpEntity<>(req, jsonHeaders()), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void loginReflectsRealTenantMemberships() {
        TenantFixture fixture = createTenantWithOwner();
        Map<String, Object> body = login(fixture.ownerEmail(), fixture.ownerPassword());
        java.util.List<Map<String, Object>> tenants = (java.util.List<Map<String, Object>>) body.get("tenants");
        assertThat(tenants).hasSize(1);
        assertThat(tenants.get(0).get("tenantId")).isEqualTo(fixture.tenantId().toString());
        assertThat(tenants.get(0).get("role")).isEqualTo("OWNER");
    }

    @Test
    void selectTenantSucceedsForRealMember() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/auth/select-tenant/" + fixture.tenantId(),
                HttpMethod.POST, new HttpEntity<>(null, authHeaders(fixture.ownerToken())), Map.class);
        // fixture.ownerToken() is already tenant-scoped; select-tenant re-derives from the
        // unscoped identity token instead — re-login to get one.
        Map<String, Object> loginBody = login(fixture.ownerEmail(), fixture.ownerPassword());
        String unscopedToken = (String) loginBody.get("token");
        ResponseEntity<Map> res2 = rest.exchange(baseUrl() + "/api/auth/select-tenant/" + fixture.tenantId(),
                HttpMethod.POST, new HttpEntity<>(null, authHeaders(unscopedToken)), Map.class);
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res2.getBody().get("role")).isEqualTo("OWNER");
    }

    @Test
    void selectTenantForNonMemberReturns403WithoutLeakingTenantExistence() {
        TenantFixture ownedByOther = createTenantWithOwner();
        String outsiderEmail = registerUser("TestPass123!", "Outsider");
        Map<String, Object> loginBody = login(outsiderEmail, "TestPass123!");
        String token = (String) loginBody.get("token");

        // Real tenant, but this user isn't a member.
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/auth/select-tenant/" + ownedByOther.tenantId(),
                HttpMethod.POST, new HttpEntity<>(null, authHeaders(token)), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Entirely made-up tenant id — should look identical to the caller (no existence leak).
        ResponseEntity<Map> res2 = rest.exchange(baseUrl() + "/api/auth/select-tenant/" + UUID.randomUUID(),
                HttpMethod.POST, new HttpEntity<>(null, authHeaders(token)), Map.class);
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void selectTenantWithoutAuthReturns401() {
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/auth/select-tenant/" + UUID.randomUUID(),
                HttpMethod.POST, new HttpEntity<>(null, jsonHeaders()), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void selectTenantWithGarbageTokenReturns401() {
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/auth/select-tenant/" + UUID.randomUUID(),
                HttpMethod.POST, new HttpEntity<>(null, authHeaders("not-a-real-jwt")), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void meWithUnscopedTokenReturnsNullTenantAndRole() {
        String email = registerUser("TestPass123!", "Someone");
        Map<String, Object> loginBody = login(email, "TestPass123!");
        String token = (String) loginBody.get("token");
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("tenantId")).isNull();
        assertThat(res.getBody().get("role")).isNull();
    }

    @Test
    void meWithScopedTokenReturnsTenantAndRole() {
        TenantFixture fixture = createTenantWithOwner();
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("tenantId")).isEqualTo(fixture.tenantId().toString());
        assertThat(res.getBody().get("role")).isEqualTo("OWNER");
    }

    @Test
    void meWithoutAuthReturns401() {
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
