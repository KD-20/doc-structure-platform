package com.docstructure.platform.guestaccess;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * guest_share_links is RLS-protected; standard finder methods here need TenantContext set
 * (callers must run them from a @TenantScoped method). findByTokenHashBypassingRls is the one
 * deliberate exception — see find_guest_share_link_by_token_hash() in V1__init_schema.sql for
 * why GuestAuthFilter needs it (the tenant is unknown until after this lookup succeeds).
 */
public interface GuestShareLinkRepository extends JpaRepository<GuestShareLink, UUID> {
    List<GuestShareLink> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    @Query(value = "SELECT * FROM find_guest_share_link_by_token_hash(:tokenHash)", nativeQuery = true)
    Optional<GuestShareLink> findByTokenHashBypassingRls(@Param("tokenHash") String tokenHash);
}
