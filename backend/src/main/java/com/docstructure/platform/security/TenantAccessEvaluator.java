package com.docstructure.platform.security;

import com.docstructure.platform.common.TenantContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Standard guard for every {tenantId} path variable: hasRole('X') alone only proves the
 * caller holds role X for WHATEVER tenant their token is scoped to, not for the tenant in
 * the URL. Every tenant-scoped controller method must combine hasRole(...) with
 * @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('...')") so a token
 * scoped to tenant A can't be replayed against tenant B's URLs.
 */
@Component("tenantAccess")
public class TenantAccessEvaluator {

    public boolean isCurrentTenant(UUID tenantId) {
        UUID current = TenantContext.getTenantId();
        return current != null && current.equals(tenantId);
    }
}
