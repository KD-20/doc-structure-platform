package com.docstructure.platform.documents;

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
 * In-memory pub/sub so the Documents page updates live instead of needing a manual refresh —
 * processing here is synchronous within a single request (see docs/DECISIONS.md), so this
 * isn't reporting background-job progress; it's telling *other* open tabs/users viewing the
 * same tenant that something changed, which polling/manual-refresh had no way to do. In-memory
 * (not a message broker) is deliberately fine at this scale: single app instance, no
 * multi-node fan-out requirement yet — revisit if the app is ever horizontally scaled.
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
        List<SseEmitter> emitters = emittersByTenant.get(tenantId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        Map<String, Object> payload = Map.of("documentId", documentId, "status", status, "docType", docType);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("document-status").data(payload));
            } catch (IOException | IllegalStateException e) {
                // Client disconnected without a clean close — drop it; onError/onCompletion
                // usually beats us to this, but a send() failure is the reliable fallback.
                log.debug("Dropping stale SSE emitter for tenant {}: {}", tenantId, e.toString());
                emitters.remove(emitter);
            }
        }
    }
}
