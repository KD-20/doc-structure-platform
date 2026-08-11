package com.docstructure.platform.support;

import com.docstructure.platform.auth.MembershipRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Validates ApiTestBase's own machinery before it's relied on by every other *IT suite. */
class ApiTestBaseSmokeIT extends ApiTestBase {

    @Test
    void createTenantWithOwnerAndAddMemberWorksEndToEnd() {
        TenantFixture fixture = createTenantWithOwner();
        assertThat(fixture.tenantId()).isNotNull();

        ResponseEntity<Map> getRes = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId(), HttpMethod.GET,
                new HttpEntity<>(authHeaders(fixture.ownerToken())), Map.class);
        assertThat(getRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getRes.getBody().get("slug")).isEqualTo(fixture.slug());

        String viewerToken = createMemberWithRole(fixture, fixture.ownerToken(), MembershipRole.VIEWER);
        ResponseEntity<Map> viewerGet = rest.exchange(baseUrl() + "/api/tenants/" + fixture.tenantId(), HttpMethod.GET,
                new HttpEntity<>(authHeaders(viewerToken)), Map.class);
        assertThat(viewerGet.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
