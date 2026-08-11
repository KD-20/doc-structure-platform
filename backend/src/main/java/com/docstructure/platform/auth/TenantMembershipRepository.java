package com.docstructure.platform.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * tenant_memberships is row-level-security protected: every method here except
 * {@link #listAllForUser(UUID)} only sees rows for whatever tenant TenantContext is bound to
 * when called, so callers must run these from a @TenantScoped @Transactional service method.
 */
public interface TenantMembershipRepository extends JpaRepository<TenantMembership, UUID> {

    Optional<TenantMembership> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    List<TenantMembership> findByTenantId(UUID tenantId);

    /** Cross-tenant by design: see list_tenant_memberships_for_user() in V1__init_schema.sql. */
    @Query(value = "SELECT tenant_id AS tenantId, role AS role FROM list_tenant_memberships_for_user(:userId)",
            nativeQuery = true)
    List<TenantMembershipRow> listAllForUser(@Param("userId") UUID userId);
}
