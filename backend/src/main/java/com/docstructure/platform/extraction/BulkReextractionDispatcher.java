package com.docstructure.platform.extraction;

import com.docstructure.platform.common.Actor;
import com.docstructure.platform.common.ActorType;
import com.docstructure.platform.common.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Fire-and-forget entry point for the automatic re-extraction case (RuleSetController, right
 * after a rule set version is created/activated) — runs on its own dedicated executor (see
 * AsyncConfig#bulkReextractionExecutor) instead of the calling HTTP thread, so saving a rule set
 * returns as soon as the version itself is written, without waiting on how many documents of
 * that type happen to exist.
 * <p>
 * A separate bean from BulkReextractionService, not a second method on it — same reason
 * ExtractionWorker is separate from ExtractionService: this method calls
 * bulkReextractionService.reextractByDocType(...), a genuinely different bean, so that call
 * crosses a real Spring proxy boundary and its @TenantScoped/@Transactional actually apply. On
 * the same bean, that would be a self-invocation and silently skip @TenantScoped's set_config
 * entirely (confirmed live: the first version of this had exactly that bug — RLS then filtered
 * out every document since the tenant was never actually bound for that inner call, and nothing
 * ever got enqueued, no error either since the query just legitimately returned zero rows).
 * <p>
 * Two pieces of thread-local request state have to be rebuilt by hand here, same as
 * ExtractionWorker (see its own javadoc for why): TenantContext, read by TenantContextAspect to
 * bind Postgres RLS, must be set on THIS thread before calling into the @TenantScoped method
 * below; and the Spring Security context is rebuilt from the triggering user's id.
 */
@Component
public class BulkReextractionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BulkReextractionDispatcher.class);

    private final BulkReextractionService bulkReextractionService;

    public BulkReextractionDispatcher(BulkReextractionService bulkReextractionService) {
        this.bulkReextractionService = bulkReextractionService;
    }

    @Async("bulkReextractionExecutor")
    public void reextractByDocTypeAsync(UUID tenantId, String docType, UUID triggeredByUserId) {
        TenantContext.setTenantId(tenantId);
        MDC.put("tenantId", tenantId.toString());
        if (triggeredByUserId != null) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    new TriggeringUserActor(triggeredByUserId), null, List.of()));
            MDC.put("userId", triggeredByUserId.toString());
        }
        try {
            bulkReextractionService.reextractByDocType(tenantId, docType, triggeredByUserId);
        } catch (RuntimeException e) {
            log.warn("async bulk re-extraction failed tenant={} docType={}: {}", tenantId, docType, e.toString());
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
            MDC.clear();
        }
    }

    private record TriggeringUserActor(UUID userId) implements Actor {
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
}
