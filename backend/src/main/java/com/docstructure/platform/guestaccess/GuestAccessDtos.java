package com.docstructure.platform.guestaccess;

import com.docstructure.platform.documents.DocumentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response/request records for the guest-facing and guest-link-admin APIs. Package-private: only this package's controllers need these. */
record GuestDocumentResponse(UUID documentId, String filename, String docType, DocumentStatus status, JsonNode fields) {
}

/** notifyEmail is optional — when set, the controller emails the guest link there in the same request (see GuestLinkController#sendNotification). Null/blank means "just create the link", same as before. */
record CreateGuestLinkRequest(@NotEmpty List<UUID> documentIds, @NotNull @Future Instant expiresAt, Integer maxUses,
                               @Email String notifyEmail) {
}

/** emailSent/emailError are null when no notifyEmail was requested; otherwise they report whether the send actually succeeded — the link itself is always created regardless, so a failed/unconfigured send never loses the token. */
record GuestLinkResponse(UUID id, List<UUID> documentIds, Instant expiresAt, Integer maxUses, int useCount,
                          boolean revoked, Instant createdAt, String token, Boolean emailSent, String emailError) {
    static GuestLinkResponse from(GuestShareLink link, List<UUID> documentIds, String token) {
        return new GuestLinkResponse(link.getId(), documentIds, link.getExpiresAt(), link.getMaxUses(),
                link.getUseCount(), link.getRevokedAt() != null, link.getCreatedAt(), token, null, null);
    }

    GuestLinkResponse withEmailResult(boolean sent, String error) {
        return new GuestLinkResponse(id, documentIds, expiresAt, maxUses, useCount, revoked, createdAt, token, sent, error);
    }
}
