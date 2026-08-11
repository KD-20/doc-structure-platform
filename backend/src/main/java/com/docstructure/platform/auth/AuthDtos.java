package com.docstructure.platform.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Request/response records for the auth API. Package-private: only AuthController needs these. */
record RegisterRequest(@NotBlank @Email String email, @NotBlank @Size(min = 8) String password,
                        @NotBlank String fullName) {
}

record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
}

record TenantMembershipSummary(UUID tenantId, String tenantName, MembershipRole role) {
}

record LoginResponse(String token, List<TenantMembershipSummary> tenants) {
}

record SelectTenantResponse(String token, UUID tenantId, MembershipRole role) {
}

record CurrentUserResponse(UUID userId, String email, UUID tenantId, MembershipRole role) {
}
