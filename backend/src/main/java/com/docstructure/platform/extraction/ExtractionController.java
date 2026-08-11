package com.docstructure.platform.extraction;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tenants/{tenantId}")
public class ExtractionController {

    private final ExtractionService extractionService;

    public ExtractionController(ExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('EDITOR')")
    @PostMapping("/documents/{documentId}/extraction-runs")
    public ResponseEntity<ExtractionRunResponse> trigger(@PathVariable UUID tenantId, @PathVariable UUID documentId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(extractionService.triggerExtraction(tenantId, documentId));
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('VIEWER')")
    @GetMapping("/documents/{documentId}/extraction-runs")
    public List<ExtractionRunResponse> listRuns(@PathVariable UUID tenantId, @PathVariable UUID documentId) {
        return extractionService.listRuns(tenantId, documentId);
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('VIEWER')")
    @GetMapping("/extraction-runs/{runId}")
    public ExtractionRunResponse getRun(@PathVariable UUID tenantId, @PathVariable UUID runId) {
        return extractionService.getRun(tenantId, runId);
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('VIEWER')")
    @GetMapping("/documents/{documentId}/extracted-data")
    public List<ExtractedDataResponse> extractedData(@PathVariable UUID tenantId, @PathVariable UUID documentId) {
        return extractionService.getExtractedData(tenantId, documentId);
    }
}
