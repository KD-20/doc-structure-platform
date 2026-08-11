package com.docstructure.platform.common;

import java.util.UUID;

/** Implemented by whatever Spring Security principal is on the request (user or guest), so AuditAspect can record who did what without caring which auth path produced it. */
public interface Actor {
    ActorType getActorType();

    UUID getUserId();

    UUID getGuestLinkId();
}
