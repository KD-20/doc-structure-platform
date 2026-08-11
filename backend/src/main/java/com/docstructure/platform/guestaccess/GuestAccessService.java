package com.docstructure.platform.guestaccess;

import com.docstructure.platform.common.ApiExceptions;
import com.docstructure.platform.common.TenantScoped;
import com.docstructure.platform.documents.Document;
import com.docstructure.platform.documents.DocumentRepository;
import com.docstructure.platform.search.ExtractedData;
import com.docstructure.platform.search.ExtractedDataRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class GuestAccessService {

    private final DocumentRepository documentRepository;
    private final ExtractedDataRepository extractedDataRepository;

    public GuestAccessService(DocumentRepository documentRepository, ExtractedDataRepository extractedDataRepository) {
        this.documentRepository = documentRepository;
        this.extractedDataRepository = extractedDataRepository;
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public GuestDocumentResponse getDocument(UUID tenantId, UUID documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Document not found"));
        ExtractedData latest = extractedDataRepository.findByTenantIdAndDocumentIdOrderByCreatedAtDesc(tenantId, documentId)
                .stream().findFirst().orElse(null);
        return new GuestDocumentResponse(doc.getId(), doc.getFilename(), doc.getDocType(), doc.getStatus(),
                latest != null ? latest.getFields() : null);
    }
}
