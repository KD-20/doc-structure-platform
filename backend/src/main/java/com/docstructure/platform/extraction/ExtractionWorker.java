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
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;

/**
 * Picks up ExtractionRequestedEvent on a background thread (see AsyncConfig's executor),
 * AFTER_COMMIT so the worker never races the PENDING run row's own INSERT.
 * <p>
 * Two pieces of thread-local request state have to be rebuilt by hand here, since neither
 * survives the hop to a fresh executor thread: TenantContext (a plain ThreadLocal read by
 * TenantContextAspect to bind Postgres RLS — see TenantContextAspect/docs/DECISIONS.md) must
 * be set BEFORE calling any @TenantScoped method, not from within it; and the Spring Security
 * context (read by AuditService when recording EXTRACTION_RUN_COMPLETED/FAILED) is rebuilt from
 * the enqueueing request's user id so those audit entries still attribute to the person who
 * triggered the run, not to "system".
 * <p>
 * performExtraction rethrows on failure instead of recording FAILED itself — deliberately, so
 * the failure is only ever recorded (via failureRecorder, its own separate REQUIRES_NEW
 * transaction) AFTER performExtraction's own transaction has actually finished rolling back and
 * released its row locks. See ExtractionService#performExtraction and
 * ExtractionFailureRecorder's javadocs for the self-deadlock this avoids.
 */
@Component
public class ExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(ExtractionWorker.class);

    private final ExtractionService extractionService;
    private final ExtractionFailureRecorder failureRecorder;

    public ExtractionWorker(ExtractionService extractionService, ExtractionFailureRecorder failureRecorder) {
        this.extractionService = extractionService;
        this.failureRecorder = failureRecorder;
    }

    @Async("extractionExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onExtractionRequested(ExtractionRequestedEvent event) {
        log.debug("picked up extraction run={} document={} tenant={} thread={}", event.runId(), event.documentId(),
                event.tenantId(), Thread.currentThread().getName());
        TenantContext.setTenantId(event.tenantId());
        MDC.put("tenantId", event.tenantId().toString());
        MDC.put("extractionRunId", event.runId().toString());
        if (event.triggeredByUserId() != null) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    new TriggeringUserActor(event.triggeredByUserId()), null, List.of()));
            MDC.put("userId", event.triggeredByUserId().toString());
        }
        try {
            extractionService.performExtraction(event.tenantId(), event.documentId(), event.runId());
        } catch (RuntimeException e) {
            // performExtraction's own transaction has already rolled back and released its
            // locks by the time an exception reaches this catch (Spring's @Transactional advice
            // unwinds before rethrowing) — safe to record FAILED here in a fresh transaction.
            failureRecorder.recordFailure(event.tenantId(), event.documentId(), event.runId(), e.getMessage());
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
