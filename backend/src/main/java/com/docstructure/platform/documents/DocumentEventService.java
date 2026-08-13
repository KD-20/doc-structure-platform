package com.docstructure.platform.documents;

import com.docstructure.platform.extraction.ExtractionRunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory pub/sub so the Documents page updates live instead of needing a manual refresh or a
 * click on the retrigger button — extraction runs asynchronously in the background (see
 * ExtractionService#enqueueExtraction), so "something changed" now includes a job merely
 * starting/still running, not just a document reaching a terminal state; this is what lets the
 * UI show a live loading indicator for a job someone else triggered, or one that was already
 * in flight when the page loaded. In-memory (not a message broker) is deliberately fine at this
 * scale: single app instance, no multi-node fan-out requirement yet — revisit if the app is
 * ever horizontally scaled.
 */
@Service
public class DocumentEventService {

    private static final Logger log = LoggerFactory.getLogger(DocumentEventService.class);

    private final Map<UUID, List<SseEmitter>> emittersByTenant = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID tenantId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout — stays open until the client disconnects
        List<SseEmitter> emitters = emittersByTenant.computeIfAbsent(tenantId, t -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    public void publishStatusChange(UUID tenantId, UUID documentId, DocumentStatus status, String docType) {
        broadcast(tenantId, "document-status", Map.of("documentId", documentId, "status", status, "docType", docType));
    }

    /**
     * Fired at every extraction-run transition (PENDING at enqueue, RUNNING at start, SUCCEEDED/
     * FAILED at completion — see ExtractionService/ExtractionFailureRecorder) — a separate event
     * from publishStatusChange because a run starting doesn't change the document's own `status`
     * field at all (PENDING/RUNNING have no DocumentStatus counterpart), so there'd otherwise be
     * no live signal that a job is in flight until it finishes.
     */
    public void publishExtractionStatus(UUID tenantId, UUID documentId, ExtractionRunStatus runStatus) {
        broadcast(tenantId, "extraction-status", Map.of("documentId", documentId, "runStatus", runStatus));
    }

    private void broadcast(UUID tenantId, String eventName, Map<String, Object> payload) {
        List<SseEmitter> emitters = emittersByTenant.get(tenantId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException | IllegalStateException e) {
                // Client disconnected without a clean close — drop it; onError/onCompletion
                // usually beats us to this, but a send() failure is the reliable fallback.
                log.debug("Dropping stale SSE emitter for tenant {}: {}", tenantId, e.toString());
                emitters.remove(emitter);
            }
        }
    }
}
