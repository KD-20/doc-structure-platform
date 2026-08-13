package com.docstructure.platform.documents;

import com.docstructure.platform.auth.AppPrincipal;
import com.docstructure.platform.common.ApiExceptions;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/tenants/{tenantId}/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentService documentService;
    private final DocumentEventService documentEventService;

    public DocumentController(DocumentService documentService, DocumentEventService documentEventService) {
        this.documentService = documentService;
        this.documentEventService = documentEventService;
    }

    /**
     * Live status updates (see DocumentEventService) — browser EventSource can't send an
     * Authorization header, so this relies on JwtAuthFilter's query-param token fallback
     * instead of the usual Bearer header (frontend passes ?token=<jwt>).
     */
    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('VIEWER')")
    @GetMapping(value = "/events", produces = "text/event-stream")
    public SseEmitter events(@PathVariable UUID tenantId) {
        return documentEventService.subscribe(tenantId);
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('EDITOR')")
    @PostMapping
    public ResponseEntity<DocumentSummaryResponse> upload(@PathVariable UUID tenantId,
                                                            @RequestPart("file") MultipartFile file,
                                                            @RequestPart(value = "docType", required = false) String docType,
                                                            @AuthenticationPrincipal AppPrincipal principal) {
        DocumentSummaryResponse response = documentService.upload(tenantId, file, docType, principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('VIEWER')")
    @GetMapping
    public Page<DocumentSummaryResponse> list(@PathVariable UUID tenantId,
                                               @RequestParam(required = false) String docType,
                                               Pageable pageable) {
        return documentService.list(tenantId, docType, pageable);
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('VIEWER')")
    @GetMapping("/{documentId}")
    public DocumentSummaryResponse get(@PathVariable UUID tenantId, @PathVariable UUID documentId) {
        return documentService.get(tenantId, documentId);
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('VIEWER')")
    @GetMapping("/{documentId}/raw-text")
    public RawTextResponse rawText(@PathVariable UUID tenantId, @PathVariable UUID documentId) {
        return documentService.getRawText(tenantId, documentId);
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('VIEWER')")
    @GetMapping("/{documentId}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID tenantId, @PathVariable UUID documentId) {
        try {
            DocumentService.DownloadHandle handle = documentService.download(tenantId, documentId);
            MediaType mediaType = handle.contentType() != null
                    ? MediaType.parseMediaType(handle.contentType())
                    : MediaType.APPLICATION_OCTET_STREAM;
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(handle.filename()).build().toString())
                    .body(new InputStreamResource(handle.content()));
        } catch (IOException e) {
            throw new ApiExceptions.NotFoundException("Stored file is unavailable");
        }
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('EDITOR')")
    @PatchMapping("/{documentId}/doc-type")
    public DocumentSummaryResponse updateDocType(@PathVariable UUID tenantId, @PathVariable UUID documentId,
                                                  @Valid @RequestBody UpdateDocTypeRequest request,
                                                  @AuthenticationPrincipal AppPrincipal principal) {
        return documentService.updateDocType(tenantId, documentId, request.docType(), principal.userId());
    }

    /**
     * Retries on a transient lock conflict with the background extraction worker (see
     * ExtractionWorker/ExtractionService#performExtraction) — deleting a document while its own
     * auto-triggered extraction is still running races the two transactions for the same
     * documents row (the worker's extracted_data INSERT takes a FOR KEY SHARE lock on it via the
     * FK), and Postgres's deadlock detector picks a victim essentially at random; sometimes
     * that's this DELETE, not the worker. Retrying (each attempt is a genuinely fresh
     * transaction via the DocumentService proxy — the previous attempt's rollback doesn't carry
     * over) is the standard, correct response to that class of conflict rather than surfacing it
     * as a 500. Not @Retryable/spring-retry: three attempts inline is simple enough not to need
     * the extra dependency.
     */
    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('ADMIN')")
    @DeleteMapping("/{documentId}")
    public void delete(@PathVariable UUID tenantId, @PathVariable UUID documentId) {
        int attempt = 0;
        while (true) {
            try {
                documentService.delete(tenantId, documentId);
                return;
            } catch (ConcurrencyFailureException e) {
                attempt++;
                if (attempt >= 3) {
                    throw e;
                }
                log.warn("delete document={} tenant={} hit a concurrency conflict, retrying (attempt {}): {}",
                        documentId, tenantId, attempt, e.toString());
                try {
                    Thread.sleep(25L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
