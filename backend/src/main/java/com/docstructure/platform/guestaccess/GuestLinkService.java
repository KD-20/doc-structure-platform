package com.docstructure.platform.guestaccess;

import com.docstructure.platform.audit.Audited;
import com.docstructure.platform.common.ApiExceptions;
import com.docstructure.platform.common.TenantScoped;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class GuestLinkService {

    private static final Logger log = LoggerFactory.getLogger(GuestLinkService.class);

    private final GuestShareLinkRepository repository;
    private final GuestTokenService tokenService;
    private final ObjectMapper objectMapper;

    public GuestLinkService(GuestShareLinkRepository repository, GuestTokenService tokenService,
                             ObjectMapper objectMapper) {
        this.repository = repository;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @TenantScoped
    @Transactional
    @Audited(action = "GUEST_LINK_CREATED", entityType = "GUEST_SHARE_LINK")
    public CreatedGuestLink create(UUID tenantId, UUID userId, List<UUID> documentIds, Instant expiresAt, Integer maxUses) {
        String rawToken = tokenService.generateToken();

        ObjectNode scope = objectMapper.createObjectNode();
        var idsArray = scope.putArray("documentIds");
        documentIds.forEach(id -> idsArray.add(id.toString()));

        GuestShareLink link = new GuestShareLink();
        link.setTenantId(tenantId);
        link.setTokenHash(tokenService.hash(rawToken));
        link.setScope(scope);
        link.setCreatedByUserId(userId);
        link.setExpiresAt(expiresAt);
        link.setMaxUses(maxUses);
        link = repository.save(link);
        log.info("guest link created id={} tenant={} documents={} expiresAt={} maxUses={}", link.getId(), tenantId,
                documentIds.size(), expiresAt, maxUses);
        return new CreatedGuestLink(link, rawToken);
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public List<GuestShareLink> list(UUID tenantId) {
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @TenantScoped
    @Transactional
    @Audited(action = "GUEST_LINK_REVOKED", entityType = "GUEST_SHARE_LINK", entityIdArgIndex = 1)
    public void revoke(UUID tenantId, UUID linkId) {
        GuestShareLink link = repository.findById(linkId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Guest link not found"));
        link.setRevokedAt(Instant.now());
        repository.save(link);
        log.info("guest link revoked id={} tenant={}", linkId, tenantId);
    }

    /** Not tenant-scoped: the tenant is unknown until this lookup succeeds — see GuestShareLinkRepository. */
    @Transactional(readOnly = true)
    public Optional<GuestShareLink> findUsableByToken(String rawToken) {
        Optional<GuestShareLink> found = repository.findByTokenHashBypassingRls(tokenService.hash(rawToken));
        if (found.isEmpty()) {
            log.debug("guest token lookup: no matching link");
            return Optional.empty();
        }
        if (!found.get().isUsable(Instant.now())) {
            log.debug("guest token lookup: link={} exists but is not usable (expired/revoked/exhausted)",
                    found.get().getId());
            return Optional.empty();
        }
        return found;
    }

    /**
     * Deliberately NOT audited (see docs/DECISIONS.md: one row per guest access would flood
     * the log) — use_count is the only record of usage. Caller must set TenantContext to the
     * link's tenant first (GuestAuthFilter does, since it just resolved that tenant from the
     * token lookup above).
     */
    @TenantScoped
    @Transactional
    public void recordUse(UUID tenantId, UUID linkId) {
        repository.findById(linkId).ifPresent(link -> {
            link.setUseCount(link.getUseCount() + 1);
            repository.save(link);
            log.debug("guest link used id={} tenant={} useCount={}", linkId, tenantId, link.getUseCount());
        });
    }

    public List<UUID> extractDocumentIds(JsonNode scope) {
        List<UUID> ids = new ArrayList<>();
        if (scope != null && scope.has("documentIds")) {
            scope.get("documentIds").forEach(node -> ids.add(UUID.fromString(node.asText())));
        }
        return ids;
    }

    /** id() lets AuditAspect's reflection-based extractIdFromResult find the new link's id (see @Audited on create() above). */
    public record CreatedGuestLink(GuestShareLink link, String rawToken) {
        public UUID id() {
            return link.getId();
        }
    }
}
