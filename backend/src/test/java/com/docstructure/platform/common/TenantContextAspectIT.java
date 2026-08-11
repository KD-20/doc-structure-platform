package com.docstructure.platform.common;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Postgres RLS + TenantContextAspect plumbing actually isolates rows across
 * tenants when the app connects as the non-superuser app_user role created in
 * V1__init_schema.sql (superusers silently bypass RLS, which is what this test would
 * catch if TransactionConfig's advice ordering or the aspect pointcut ever regresses).
 *
 * Runs against the docker-compose "db" service rather than Testcontainers: this dev
 * sandbox's Docker Desktop /info API isn't compatible with the docker-java client
 * Testcontainers 1.20.3 ships (fails before any container even starts), independent of
 * this project. Start the real dependency first: `docker compose up -d db` from the repo
 * root, then `mvn test -Dtest=TenantContextAspectIT`. Uses application.yml's default
 * connection properties (localhost:5432, app_user / docstructure superuser) unchanged.
 */
@SpringBootTest
@Import(TenantContextAspectIT.TestTenantService.class)
class TenantContextAspectIT {

    @Autowired
    TestTenantService testTenantService;

    @Test
    void tenantContextIsolatesRowsAcrossTenants() {
        UUID tenant1 = UUID.randomUUID();
        UUID tenant2 = UUID.randomUUID();
        UUID user1 = UUID.randomUUID();

        testTenantService.seedTenantsAndUser(tenant1, tenant2, user1);

        TenantContext.setTenantId(tenant1);
        try {
            testTenantService.seedMembership(tenant1, user1, "OWNER");
        } finally {
            TenantContext.clear();
        }

        TenantContext.setTenantId(tenant1);
        try {
            assertThat(testTenantService.membershipTenantIds()).containsExactly(tenant1);
        } finally {
            TenantContext.clear();
        }

        TenantContext.setTenantId(tenant2);
        try {
            assertThat(testTenantService.membershipTenantIds()).isEmpty();
        } finally {
            TenantContext.clear();
        }

        assertThat(testTenantService.membershipTenantIdsNoContext()).isEmpty();
    }

    @Service
    public static class TestTenantService {
        @PersistenceContext
        private EntityManager em;

        @Transactional
        public void seedTenantsAndUser(UUID tenant1, UUID tenant2, UUID user1) {
            em.createNativeQuery("INSERT INTO tenants(id, name, slug) VALUES (?1, 'T1', ?2), (?3, 'T2', ?4)")
                    .setParameter(1, tenant1).setParameter(2, "t1-" + tenant1)
                    .setParameter(3, tenant2).setParameter(4, "t2-" + tenant2)
                    .executeUpdate();
            em.createNativeQuery("INSERT INTO users(id, email, password_hash, full_name) VALUES (?1, ?2, 'x', 'A')")
                    .setParameter(1, user1).setParameter(2, user1 + "@test.local")
                    .executeUpdate();
        }

        @Transactional
        @TenantScoped
        public void seedMembership(UUID tenantId, UUID userId, String role) {
            em.createNativeQuery("INSERT INTO tenant_memberships(tenant_id, user_id, role) VALUES (?1, ?2, ?3)")
                    .setParameter(1, tenantId).setParameter(2, userId).setParameter(3, role)
                    .executeUpdate();
        }

        @Transactional
        @TenantScoped
        @SuppressWarnings("unchecked")
        public List<UUID> membershipTenantIds() {
            return em.createNativeQuery("SELECT tenant_id FROM tenant_memberships").getResultList();
        }

        @Transactional
        @SuppressWarnings("unchecked")
        public List<UUID> membershipTenantIdsNoContext() {
            return em.createNativeQuery("SELECT tenant_id FROM tenant_memberships").getResultList();
        }
    }
}
