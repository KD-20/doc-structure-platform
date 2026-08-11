package com.docstructure.platform.publicdemo;

import com.docstructure.platform.common.ApiExceptions;
import com.docstructure.platform.documents.DocumentService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * No @PreAuthorize anywhere here on purpose — this is the one deliberately unauthenticated
 * surface in the app (permitAll in SecurityConfig), scoped entirely by the caller-supplied
 * X-Device-Id header rather than a JWT or guest-link token. See PublicDemoService's class
 * javadoc for why that means manual TenantContext handling instead of @TenantScoped.
 */
@RestController
@RequestMapping("/api/public/documents")
public class PublicDemoController {

    private final PublicDemoService publicDemoService;

    public PublicDemoController(PublicDemoService publicDemoService) {
        this.publicDemoService = publicDemoService;
    }

    @PostMapping
    public ResponseEntity<PublicDocumentResponse> upload(@RequestHeader("X-Device-Id") String deviceId,
                                                           @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(publicDemoService.upload(deviceId, file));
    }

    @GetMapping
    public List<PublicDocumentResponse> list(@RequestHeader("X-Device-Id") String deviceId) {
        return publicDemoService.list(deviceId);
    }

    @GetMapping("/{documentId}/extraction-runs")
    public List<PublicExtractionRunResponse> extractionRuns(@RequestHeader("X-Device-Id") String deviceId,
                                                              @PathVariable UUID documentId) {
        return publicDemoService.listRuns(deviceId, documentId);
    }

    @GetMapping("/{documentId}/extracted-data")
    public List<PublicExtractedDataResponse> extractedData(@RequestHeader("X-Device-Id") String deviceId,
                                                             @PathVariable UUID documentId) {
        return publicDemoService.extractedData(deviceId, documentId);
    }

    @GetMapping("/{documentId}/download")
    public ResponseEntity<InputStreamResource> download(@RequestHeader("X-Device-Id") String deviceId,
                                                          @PathVariable UUID documentId) {
        try {
            DocumentService.DownloadHandle handle = publicDemoService.download(deviceId, documentId);
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
}
