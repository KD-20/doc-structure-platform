package com.docstructure.platform.guestaccess;

import com.docstructure.platform.auth.AppPrincipal;
import com.docstructure.platform.notifications.EmailService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/tenants/{tenantId}/guest-links")
public class GuestLinkController {

    private final GuestLinkService guestLinkService;
    private final Optional<EmailService> emailService;

    public GuestLinkController(GuestLinkService guestLinkService, Optional<EmailService> emailService) {
        this.guestLinkService = guestLinkService;
        this.emailService = emailService;
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<GuestLinkResponse> create(@PathVariable UUID tenantId,
                                                      @Valid @RequestBody CreateGuestLinkRequest request,
                                                      @AuthenticationPrincipal AppPrincipal principal,
                                                      HttpServletRequest httpRequest) {
        GuestLinkService.CreatedGuestLink created = guestLinkService.create(tenantId, principal.userId(),
                request.documentIds(), request.expiresAt(), request.maxUses());
        GuestLinkResponse response = GuestLinkResponse.from(created.link(), request.documentIds(), created.rawToken());

        // The link is already created and returned either way — email is a best-effort
        // convenience on top of it, never a reason to lose the (shown-once) raw token. The
        // guest URL can only be built here, right now: it needs the raw token, which is never
        // stored/retrievable again after this response.
        if (request.notifyEmail() != null && !request.notifyEmail().isBlank()) {
            String guestUrl = buildGuestUrl(httpRequest, created.rawToken(), request.documentIds());
            response = sendNotification(response, request.notifyEmail(), guestUrl, request.documentIds().size(),
                    request.expiresAt());
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('ADMIN')")
    @GetMapping
    public List<GuestLinkResponse> list(@PathVariable UUID tenantId) {
        return guestLinkService.list(tenantId).stream()
                .map(link -> GuestLinkResponse.from(link, guestLinkService.extractDocumentIds(link.getScope()), null))
                .toList();
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('ADMIN')")
    @DeleteMapping("/{linkId}")
    public void revoke(@PathVariable UUID tenantId, @PathVariable UUID linkId) {
        guestLinkService.revoke(tenantId, linkId);
    }

    private GuestLinkResponse sendNotification(GuestLinkResponse response, String to, String guestUrl,
                                                int documentCount, Instant expiresAt) {
        if (emailService.isEmpty()) {
            return response.withEmailResult(false,
                    "Email sending isn't configured on this deployment (no SMTP settings) — copy the link and share it manually instead.");
        }
        try {
            String plural = documentCount == 1 ? "" : "s";
            String subject = documentCount + " document" + plural + " shared with you";
            String body = "You've been shared " + documentCount + " document" + plural + ".\n\n"
                    + "You don't need to create an account or sign in — just open the link below:\n\n"
                    + guestUrl + "\n\n"
                    + "This link expires " + expiresAt + ".";
            emailService.get().send(to, subject, body);
            return response.withEmailResult(true, null);
        } catch (Exception e) {
            return response.withEmailResult(false, "Failed to send email: " + e.getMessage());
        }
    }

    /** Mirrors the guest URL the frontend itself builds (window.location.origin + /guest/{token}/documents/{id}) — see GuestLinksPage.tsx. */
    private String buildGuestUrl(HttpServletRequest request, String token, List<UUID> documentIds) {
        String origin = ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
        UUID firstDocId = documentIds.get(0);
        return origin + "/guest/" + token + "/documents/" + firstDocId;
    }
}
