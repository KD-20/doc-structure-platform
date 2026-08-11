package com.docstructure.platform.common;

import java.util.UUID;

/**
 * Holds the current request's tenant id for the duration of the thread handling it.
 * Populated by JwtAuthFilter/GuestAuthFilter, read by TenantContextAspect to bind
 * Postgres row-level security via set_config('app.current_tenant_id', ...).
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(UUID tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static UUID getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
