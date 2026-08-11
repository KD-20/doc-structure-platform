package com.docstructure.platform.guestaccess;

import com.docstructure.platform.common.Actor;
import com.docstructure.platform.common.ActorType;

import java.util.List;
import java.util.UUID;

public record GuestPrincipal(UUID guestLinkId, UUID tenantId, List<UUID> documentIds) implements Actor {

    @Override
    public ActorType getActorType() {
        return ActorType.GUEST;
    }

    @Override
    public UUID getUserId() {
        return null;
    }

    @Override
    public UUID getGuestLinkId() {
        return guestLinkId;
    }
}
