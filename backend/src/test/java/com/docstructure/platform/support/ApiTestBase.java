package com.docstructure.platform.support;

import com.docstructure.platform.common.TenantContext;
import com.docstructure.platform.common.TenantScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared HTTP-boundary test infrastructure: real Spring context, real embedded server, real
 * Postgres (the docker-compose "db" service — same reasoning as TenantContextAspectIT: this dev
 * sandbox's Docker Desktop isn't Testcontainers-compatible). Runs the full filter chain
 * (JwtAuthFilter, @PreAuthorize, RLS) exactly like the deployed app, rather than mocking any of
 * it — RLS/native-SQL-heavy code (SearchQueryBuilder, TsQueryExpr) can't be meaningfully verified
 * any other way.
 * <p>
 * Prerequisite: `docker compose up -d db` from the repo root before running these (same as
 * TenantContextAspectIT). Each test method creates its own uniquely-named tenant/user(s) — no
 * shared fixtures, no ordering dependencies between tests — and {@link #cleanUp()} deletes all
 * tenant-owned content afterward so the shared dev database (with real demo data the user has
 * been using all session) never accumulates real content from test runs. The one exception,
 * discovered live: the empty tenants/users shell rows themselves survive (see CleanupService) —
 * audit_log's append-only trigger blocks the FK cascade that deleting them would trigger, and
 * that's by design, not a gap. They're harmless and unmistakably test data (uniquely "it-*"
 * named) — not worth a more surgical per-user "delete if never audited" attempt for the same
 * reason it's not worth one for tenants.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApiTestBase.CleanupService.class)
public abstract class ApiTestBase {

    @LocalServerPort
    protected int port;

    /**
     * A hand-built RestTemplate, not the autowired TestRestTemplate: the latter's default
     * request factory (HttpURLConnection-based) has a long-standing JDK limitation — it can't
     * read an error response body after streaming a request, throwing "cannot retry due to
     * server authentication, in streaming mode" instead of surfacing the actual 401/403/400
     * (caught by real test failures here — not a bug in the API, a client-side artifact).
     * SimpleClientHttpRequestFactory's setBufferRequestBody/setOutputStreaming are silent
     * no-ops as of Spring Framework 6 (confirmed via javap — no backing fields left), so
     * reconfiguring it doesn't help. JdkClientHttpRequestFactory (java.net.http.HttpClient,
     * built into the JDK since 11, no extra dependency) doesn't share this bug at all. A no-op
     * error handler makes 4xx/5xx come back as a normal ResponseEntity instead of throwing,
     * matching TestRestTemplate's usual behavior.
     */
    protected RestTemplate rest;

    @Autowired
    protected CleanupService cleanupService;

    private final List<UUID> tenantsToClean = new ArrayList<>();

    @BeforeEach
    void setUpRestTemplate() {
        RestTemplate template = new RestTemplate(new JdkClientHttpRequestFactory());
        template.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }

            @Override
            public void handleError(ClientHttpResponse response) {
            }
        });
        this.rest = template;
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /** Every generated email/slug in a test run is unique, so parallel/repeat runs never collide with each other or with real "Demo Tenant" data. */
    protected String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    protected HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    protected HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * Registers a fresh user with a unique ("it-*@example.test") email. Not tracked for
     * cleanup — see the class-level javadoc: user rows are left in place permanently, same
     * reasoning as tenant shell rows, so there's nothing to track here.
     */
    protected String registerUser(String password, String fullName) {
        String email = "it-" + uniqueSuffix() + "@example.test";
        ResponseEntity<Void> res = rest.postForEntity(baseUrl() + "/api/auth/register",
                new HttpEntity<>(Map.of("email", email, "password", password, "fullName", fullName), jsonHeaders()),
                Void.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return email;
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> login(String email, String password) {
        ResponseEntity<Map> res = rest.postForEntity(baseUrl() + "/api/auth/login",
                new HttpEntity<>(Map.of("email", email, "password", password), jsonHeaders()),
                Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return res.getBody();
    }

    /** Registers + logs in + creates a fresh uniquely-slugged tenant, returning an OWNER-scoped token, the tenantId, and the owner's email/password for further logins. */
    protected TenantFixture createTenantWithOwner() {
        String password = "TestPass123!";
        String email = registerUser(password, "IT Owner");
        Map<String, Object> loginBody = login(email, password);
        String unscopedToken = (String) loginBody.get("token");

        String slug = "it-" + uniqueSuffix();
        Map<String, Object> createReq = Map.of("name", "IT Tenant " + slug, "slug", slug);
        ResponseEntity<Map> res = rest.exchange(baseUrl() + "/api/tenants", HttpMethod.POST,
                new HttpEntity<>(createReq, authHeaders(unscopedToken)), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = res.getBody();
        Map<String, Object> tenant = (Map<String, Object>) body.get("tenant");
        UUID tenantId = UUID.fromString((String) tenant.get("id"));
        tenantsToClean.add(tenantId);
        String ownerToken = (String) body.get("token");
        return new TenantFixture(tenantId, slug, email, password, ownerToken);
    }

    /** Registers a fresh user, adds them to the given tenant with the given role (via an existing ADMIN+ token), and returns a role-scoped token for them. */
    protected String createMemberWithRole(TenantFixture tenant, String adminToken, com.docstructure.platform.auth.MembershipRole role) {
        String password = "TestPass123!";
        String email = registerUser(password, role + " Member");
        ResponseEntity<Map> addRes = rest.exchange(
                baseUrl() + "/api/tenants/" + tenant.tenantId() + "/members", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "role", role.name()), authHeaders(adminToken)), Map.class);
        assertThat(addRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> loginBody = login(email, password);
        String unscopedToken = (String) loginBody.get("token");
        ResponseEntity<Map> selectRes = rest.exchange(
                baseUrl() + "/api/auth/select-tenant/" + tenant.tenantId(), HttpMethod.POST,
                new HttpEntity<>(null, authHeaders(unscopedToken)), Map.class);
        assertThat(selectRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) selectRes.getBody().get("token");
    }

    protected MultiValueMap<String, Object> multipart(Map<String, Object> parts) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        parts.forEach(form::add);
        return form;
    }

    /**
     * Extraction runs asynchronously now (see ExtractionService#enqueueExtraction/
     * ExtractionWorker) — POST .../extraction-runs returns 202 with a PENDING body immediately,
     * not a finished result. Tests that need the outcome poll this instead of asserting on the
     * POST response directly. Also matters for cleanup correctness: a test that returns without
     * waiting risks @AfterEach's cleanUp() deleting the run/document out from under a
     * still-in-flight background task (observed live as a StaleObjectStateException logged by
     * ExtractionWorker) — always wait for terminal status before a test method returns if it
     * triggered extraction at all, even if the test doesn't care about the outcome.
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> waitForTerminalRunStatus(UUID tenantId, String token, UUID runId) {
        String url = baseUrl() + "/api/tenants/" + tenantId + "/extraction-runs/" + runId;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<Map> res = rest.exchange(url, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), Map.class);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> body = res.getBody();
            String status = (String) body.get("status");
            if ("SUCCEEDED".equals(status) || "FAILED".equals(status)) {
                return body;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("Extraction run " + runId + " did not reach a terminal status within 5s");
    }

    /**
     * For callers that trigger extraction indirectly (upload's auto-trigger, updateDocType's
     * re-trigger) and never see a run id directly — finds the most recent run for the document
     * (list is ordered newest-first) and waits for it. A brief settle delay first, since the
     * enqueueing request may return before ExtractionWorker's AFTER_COMMIT listener has even
     * created... no, PENDING is created synchronously in the same request — but polling
     * immediately could still catch the list before that PENDING row's own transaction commits
     * in a pathological case, so this retries the "list is empty" case too, not just PENDING/RUNNING.
     */
    @SuppressWarnings("unchecked")
    protected void waitForLatestRunToFinish(UUID tenantId, String token, UUID documentId) {
        String url = baseUrl() + "/api/tenants/" + tenantId + "/documents/" + documentId + "/extraction-runs";
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<List> res = rest.exchange(url, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), List.class);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<Map<String, Object>> runs = res.getBody();
            if (runs != null && !runs.isEmpty()) {
                UUID latestRunId = UUID.fromString((String) runs.get(0).get("id"));
                waitForTerminalRunStatus(tenantId, token, latestRunId);
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("No extraction run appeared for document " + documentId + " within 5s");
    }

    /**
     * Sets TenantContext, calls into the (genuinely separate, Spring-proxied) CleanupService
     * bean, then clears it — the same pattern AuthController#selectTenant and
     * TenantController#create use. Required because TenantContextAspect reads
     * TenantContext.getTenantId() at the moment its @Around advice fires, i.e. before a
     * @TenantScoped method's own body runs: setting TenantContext inside that same method is
     * one step too late and silently no-ops every RLS-scoped query in it (confirmed live —
     * see backdateGuestLinkExpiry below, caught when a "backdate then verify expired" test
     * passed for the wrong reason before this fix: the UPDATE silently affected zero rows).
     */
    protected void withTenantContext(UUID tenantId, Runnable action) {
        TenantContext.setTenantId(tenantId);
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    /** See CleanupService#backdateGuestLinkExpiry for why this needs the withTenantContext wrapper. */
    protected void backdateGuestLinkExpiry(UUID tenantId, UUID linkId, java.time.Instant expiresAt) {
        withTenantContext(tenantId, () -> cleanupService.backdateGuestLinkExpiry(linkId, expiresAt));
    }

    @AfterEach
    void cleanUp() {
        for (UUID tenantId : tenantsToClean) {
            withTenantContext(tenantId, () -> cleanupService.deleteTenantScopedData(tenantId));
        }
        tenantsToClean.clear();
    }

    public record TenantFixture(UUID tenantId, String slug, String ownerEmail, String ownerPassword, String ownerToken) {
    }

    /**
     * Runs as app_user (the app's own runtime role, same as production) — RLS-scoped deletes on
     * the eight tenant-scoped tables (extracted_data, extraction_runs, extraction_rule_sets,
     * guest_share_links, documents, tenant_memberships — audit_log is append-only, see below)
     * only affect the given tenant's rows once TenantContext is set; only tenants/users
     * themselves have no RLS.
     */
    @Component
    public static class CleanupService {
        @PersistenceContext
        private EntityManager em;

        /**
         * Everything about a test tenant except the tenants row and its audit_log trail — must
         * be called with TenantContext already set by the caller (see
         * ApiTestBase#withTenantContext) and carries @TenantScoped itself so
         * TenantContextAspect actually runs set_config(...) before these DELETEs execute.
         * Originally set TenantContext inside this same method's body instead (and separately,
         * inside a second method that deleted tenant_memberships without any context at all,
         * wrongly assuming — like tenants/users — it had no RLS; it does), which silently
         * turned every DELETE here into a zero-row no-op (RLS's USING clause compares tenant_id
         * against an unset/null session variable, matching nothing). Caught live via a
         * guest-link backdate test that kept passing for the wrong reason — the "expired" token
         * was never actually backdated by that separate bug, but this exact class of mistake is
         * what motivated checking DELETE row counts here at all.
         * <p>
         * Doesn't delete the tenants row itself: audit_log has an FK to tenants, and deleting
         * the tenant row would cascade into deleting its audit_log rows, which the append-only
         * trigger (reject_audit_log_mutation(), see V1__init_schema.sql) unconditionally
         * rejects regardless of role — not even a superuser can bypass it, since the trigger
         * doesn't check role at all. There is no way to fully tear down a tenant that has any
         * audit history, which is itself intentional (an audit trail outliving the thing it
         * describes is the point), not a gap to work around. The leftover shell row is
         * harmless: no documents/members/other content after this method runs, uniquely "it-*"
         * slugged so it's never confused with real tenant data.
         */
        @TenantScoped
        @Transactional
        public void deleteTenantScopedData(UUID tenantId) {
            for (String table : List.of("extracted_data", "extraction_runs", "extraction_rule_sets",
                    "guest_share_links", "documents", "tenant_memberships")) {
                em.createNativeQuery("DELETE FROM " + table).executeUpdate();
            }
        }

        /**
         * Backdates a guest link's expiry — the create endpoint requires @Future, so this is
         * the only way to exercise "already expired" without waiting for real time to pass.
         * Has to live on this bean (not a plain method on the test class) for the same reason
         * deleteTenantScopedData does: JUnit constructs the test instance directly, no Spring
         * AOP proxy wraps it, so neither @Transactional nor @TenantScoped would ever fire if
         * this method (or the TenantContext.setTenantId call) lived on ApiTestBase itself —
         * confirmed live (TransactionRequiredException, then a silently-no-op'd UPDATE) before
         * moving it here and requiring the caller to set TenantContext first (see
         * ApiTestBase#backdateGuestLinkExpiry).
         */
        @TenantScoped
        @Transactional
        public void backdateGuestLinkExpiry(UUID linkId, java.time.Instant expiresAt) {
            em.createNativeQuery("UPDATE guest_share_links SET expires_at = :expiresAt WHERE id = :id")
                    .setParameter("expiresAt", expiresAt)
                    .setParameter("id", linkId)
                    .executeUpdate();
        }
    }
}
