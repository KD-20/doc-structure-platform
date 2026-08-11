package com.docstructure.platform.tenancy;

import com.docstructure.platform.auth.MembershipRole;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/** Request/response records for the tenancy API. Package-private: only TenantController needs these. */
record CreateTenantRequest(@NotBlank String name,
                            @NotBlank @Pattern(regexp = "^[a-z0-9]([a-z0-9-]{0,48}[a-z0-9])?$") String slug) {
}

record TenantResponse(UUID id, String name, String slug, TenantStatus status) {
    static TenantResponse from(Tenant t) {
        return new TenantResponse(t.getId(), t.getName(), t.getSlug(), t.getStatus());
    }
}

record MyTenantSummary(UUID tenantId, String tenantName, MembershipRole role) {
}

record UpdateTenantSettingsRequest(@NotNull JsonNode settings) {
}

record AddMemberRequest(@NotBlank @Email String email, @NotNull MembershipRole role) {
}

record UpdateMemberRoleRequest(@NotNull MembershipRole role) {
}

record MemberResponse(UUID userId, String email, String fullName, MembershipRole role) {
}

record CreateTenantResponse(TenantResponse tenant, String token) {
}
