package com.docstructure.platform.audit;

import com.docstructure.platform.common.ActorType;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** Response record for the audit-log API. Package-private: only AuditController needs this. */
record AuditLogResponse(UUID id, ActorType actorType, UUID actorUserId, UUID actorGuestLinkId, String action,
                         String entityType, UUID entityId, JsonNode metadata, Instant createdAt) {
    static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(log.getId(), log.getActorType(), log.getActorUserId(), log.getActorGuestLinkId(),
                log.getAction(), log.getEntityType(), log.getEntityId(), log.getMetadata(), log.getCreatedAt());
    }
}
