package com.docstructure.platform.auth;

import java.util.UUID;

/** Projection for the list_tenant_memberships_for_user(uuid) SECURITY DEFINER function. */
public interface TenantMembershipRow {
    UUID getTenantId();

    String getRole();
}
