package com.docstructure.platform.publicdemo;

import com.docstructure.platform.auth.User;
import com.docstructure.platform.auth.UserRepository;
import com.docstructure.platform.common.ApiExceptions;
import com.docstructure.platform.common.TenantContext;
import com.docstructure.platform.config.PublicDemoInitializer;
import com.docstructure.platform.documents.Document;
import com.docstructure.platform.documents.DocumentService;
import com.docstructure.platform.documents.DocumentSummaryResponse;
import com.docstructure.platform.tenancy.Tenant;
import com.docstructure.platform.tenancy.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * The anonymous "try it before you subscribe" path: no login, no admin-created share link —
 * just a client-generated device id (X-Device-Id header, see PublicDemoController) scoping a
 * small number of uploads into the one shared "Public Demo" tenant that PublicDemoInitializer
 * seeds. Deliberately NOT @TenantScoped itself: this class resolves the tenant and sets
 * TenantContext, then delegates every RLS-protected query to PublicDemoQueries (a separate
 * bean) — see that class's javadoc for why it can't just call its own @TenantScoped methods.
 */
@Service
public class PublicDemoService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PublicDemoQueries queries;
    private final int maxUploadsPerDevice;

    public PublicDemoService(TenantRepository tenantRepository, UserRepository userRepository,
                              PublicDemoQueries queries,
                              @Value("${platform.public-demo.max-uploads-per-device}") int maxUploadsPerDevice) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.queries = queries;
        this.maxUploadsPerDevice = maxUploadsPerDevice;
    }

    public PublicDocumentResponse upload(String deviceId, MultipartFile file) {
        Tenant tenant = requireTenant();
        TenantContext.setTenantId(tenant.getId());
        try {
            long used = queries.countByDevice(tenant.getId(), deviceId);
            if (used >= maxUploadsPerDevice) {
                throw new ApiExceptions.ValidationException(
                        "You've reached the " + maxUploadsPerDevice
                                + "-document limit for trying this out without an account — register for unlimited uploads.");
            }
            DocumentSummaryResponse created = queries.uploadAndTag(tenant.getId(), file, requireAnonUser().getId(), deviceId);
            Document doc = queries.findOwned(tenant.getId(), deviceId, created.id())
                    .orElseThrow(() -> new ApiExceptions.NotFoundException("Document not found"));
            return PublicDocumentResponse.from(doc, (int) used + 1, maxUploadsPerDevice);
        } finally {
            TenantContext.clear();
        }
    }

    public List<PublicDocumentResponse> list(String deviceId) {
        Tenant tenant = requireTenant();
        TenantContext.setTenantId(tenant.getId());
        try {
            long used = queries.countByDevice(tenant.getId(), deviceId);
            return queries.listByDevice(tenant.getId(), deviceId).stream()
                    .map(d -> PublicDocumentResponse.from(d, (int) used, maxUploadsPerDevice))
                    .toList();
        } finally {
            TenantContext.clear();
        }
    }

    public List<PublicExtractionRunResponse> listRuns(String deviceId, UUID documentId) {
        Tenant tenant = requireTenant();
        TenantContext.setTenantId(tenant.getId());
        try {
            requireOwnedDocument(tenant.getId(), deviceId, documentId);
            return queries.listRuns(tenant.getId(), documentId).stream()
                    .map(PublicExtractionRunResponse::from).toList();
        } finally {
            TenantContext.clear();
        }
    }

    public List<PublicExtractedDataResponse> extractedData(String deviceId, UUID documentId) {
        Tenant tenant = requireTenant();
        TenantContext.setTenantId(tenant.getId());
        try {
            requireOwnedDocument(tenant.getId(), deviceId, documentId);
            return queries.listExtractedData(tenant.getId(), documentId).stream()
                    .map(PublicExtractedDataResponse::from).toList();
        } finally {
            TenantContext.clear();
        }
    }

    public DocumentService.DownloadHandle download(String deviceId, UUID documentId) throws java.io.IOException {
        Tenant tenant = requireTenant();
        TenantContext.setTenantId(tenant.getId());
        try {
            requireOwnedDocument(tenant.getId(), deviceId, documentId);
            return queries.download(tenant.getId(), documentId);
        } finally {
            TenantContext.clear();
        }
    }

    /** 404, not 403: a device shouldn't be able to tell "not yours" from "doesn't exist" for another device's trial upload. */
    private void requireOwnedDocument(UUID tenantId, String deviceId, UUID documentId) {
        queries.findOwned(tenantId, deviceId, documentId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Document not found"));
    }

    private Tenant requireTenant() {
        return tenantRepository.findBySlug(PublicDemoInitializer.TENANT_SLUG)
                .orElseThrow(() -> new ApiExceptions.ValidationException(
                        "Anonymous trial mode isn't available on this deployment right now."));
    }

    private User requireAnonUser() {
        return userRepository.findByEmail(PublicDemoInitializer.ANON_USER_EMAIL)
                .orElseThrow(() -> new ApiExceptions.ValidationException(
                        "Anonymous trial mode isn't available on this deployment right now."));
    }
}
