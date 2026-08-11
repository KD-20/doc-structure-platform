package com.docstructure.platform.audit;

import com.docstructure.platform.common.Actor;
import com.docstructure.platform.common.ActorType;
import com.docstructure.platform.common.TenantContext;
import com.docstructure.platform.common.TenantScoped;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * TenantContext.getTenantId() may be null (SYSTEM/bootstrap events — allowed by the
     * audit_log RLS policy's tenant_id IS NULL carve-out). @TenantScoped is a no-op in that
     * case, harmless either way, and covers the (already-tenant-scoped-by-caller) normal case
     * defensively even though the caller's own transaction usually already has the GUC set.
     */
    @TenantScoped
    @Transactional
    public void record(String action, String entityType, UUID entityId, Map<String, Object> metadata) {
        Actor actor = currentActor();
        AuditLog log = new AuditLog();
        log.setTenantId(TenantContext.getTenantId());
        log.setActorType(actor != null ? actor.getActorType() : ActorType.SYSTEM);
        log.setActorUserId(actor != null ? actor.getUserId() : null);
        log.setActorGuestLinkId(actor != null ? actor.getGuestLinkId() : null);
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setMetadata(objectMapper.valueToTree(metadata != null ? metadata : Map.of()));
        repository.save(log);
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public Page<AuditLog> list(UUID tenantId, String entityType, Pageable pageable) {
        return entityType != null && !entityType.isBlank()
                ? repository.findByTenantIdAndEntityTypeOrderByCreatedAtDesc(tenantId, entityType, pageable)
                : repository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
    }

    private Actor currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Actor actor ? actor : null;
    }
}
