package com.docstructure.platform.auth;

import com.docstructure.platform.common.Actor;
import com.docstructure.platform.common.ActorType;

import java.util.UUID;

/**
 * Spring Security principal for a JWT-authenticated request. tenantId/role are null for a
 * pre-tenant-selection token (only valid against /api/auth/select-tenant and tenant-listing
 * endpoints), populated for a tenant-scoped token.
 */
public record AppPrincipal(UUID userId, String email, UUID tenantId, MembershipRole role) implements Actor {

    @Override
    public ActorType getActorType() {
        return ActorType.USER;
    }

    @Override
    public UUID getUserId() {
        return userId;
    }

    @Override
    public UUID getGuestLinkId() {
        return null;
    }
}
