package com.docstructure.platform.publicdemo;

import com.docstructure.platform.common.TenantScoped;
import com.docstructure.platform.documents.Document;
import com.docstructure.platform.documents.DocumentRepository;
import com.docstructure.platform.documents.DocumentService;
import com.docstructure.platform.documents.DocumentSummaryResponse;
import com.docstructure.platform.extraction.ExtractionRun;
import com.docstructure.platform.extraction.ExtractionRunRepository;
import com.docstructure.platform.search.ExtractedData;
import com.docstructure.platform.search.ExtractedDataRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The actual RLS-protected queries behind the anonymous trial flow, split out into their own
 * bean deliberately: TenantContextAspect's @TenantScoped advice runs set_config() at the
 * *start* of the intercepted method call, before that method's body executes — so a caller
 * that sets TenantContext.setTenantId(...) and then calls one of its own @TenantScoped
 * methods (self-invocation, or here, TenantContext set too late in the same method) never
 * actually gets picked up; the set_config already ran against whatever TenantContext held
 * before. PublicDemoService resolves the tenant and sets TenantContext *before* calling into
 * this separate bean, so the call crosses a real Spring proxy and the aspect fires correctly
 * with the right value already in place. Caught by an actual end-to-end check (upload, then
 * list/lookup returning empty) — not something a green compile or unit test would show.
 */
@Service
class PublicDemoQueries {

    private final DocumentRepository documentRepository;
    private final DocumentService documentService;
    private final ExtractionRunRepository extractionRunRepository;
    private final ExtractedDataRepository extractedDataRepository;

    PublicDemoQueries(DocumentRepository documentRepository, DocumentService documentService,
                       ExtractionRunRepository extractionRunRepository,
                       ExtractedDataRepository extractedDataRepository) {
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.extractionRunRepository = extractionRunRepository;
        this.extractedDataRepository = extractedDataRepository;
    }

    @TenantScoped
    @Transactional
    DocumentSummaryResponse uploadAndTag(UUID tenantId, MultipartFile file, UUID uploaderId, String deviceId) {
        DocumentSummaryResponse created = documentService.upload(tenantId, file, null, uploaderId);
        Document doc = documentRepository.findById(created.id()).orElseThrow();
        doc.setDeviceId(deviceId);
        documentRepository.save(doc);
        return created;
    }

    @TenantScoped
    @Transactional(readOnly = true)
    long countByDevice(UUID tenantId, String deviceId) {
        return documentRepository.countByTenantIdAndDeviceId(tenantId, deviceId);
    }

    @TenantScoped
    @Transactional(readOnly = true)
    List<Document> listByDevice(UUID tenantId, String deviceId) {
        return documentRepository.findByTenantIdAndDeviceIdOrderByCreatedAtDesc(tenantId, deviceId);
    }

    @TenantScoped
    @Transactional(readOnly = true)
    Optional<Document> findOwned(UUID tenantId, String deviceId, UUID documentId) {
        return documentRepository.findById(documentId)
                .filter(d -> d.getTenantId().equals(tenantId) && deviceId.equals(d.getDeviceId()));
    }

    @TenantScoped
    @Transactional(readOnly = true)
    List<ExtractionRun> listRuns(UUID tenantId, UUID documentId) {
        return extractionRunRepository.findByTenantIdAndDocumentIdOrderByCreatedAtDesc(tenantId, documentId);
    }

    @TenantScoped
    @Transactional(readOnly = true)
    List<ExtractedData> listExtractedData(UUID tenantId, UUID documentId) {
        return extractedDataRepository.findByTenantIdAndDocumentIdOrderByCreatedAtDesc(tenantId, documentId);
    }

    @TenantScoped
    @Transactional(readOnly = true)
    DocumentService.DownloadHandle download(UUID tenantId, UUID documentId) throws IOException {
        return documentService.download(tenantId, documentId);
    }
}
