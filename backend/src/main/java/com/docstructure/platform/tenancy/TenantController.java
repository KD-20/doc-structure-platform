package com.docstructure.platform.tenancy;

import com.docstructure.platform.auth.AppPrincipal;
import com.docstructure.platform.auth.JwtService;
import com.docstructure.platform.auth.MembershipRole;
import com.docstructure.platform.common.ApiExceptions;
import com.docstructure.platform.common.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tenants")
public class TenantController {

    private final TenantService tenantService;
    private final JwtService jwtService;

    public TenantController(TenantService tenantService, JwtService jwtService) {
        this.tenantService = tenantService;
        this.jwtService = jwtService;
    }

    @PostMapping
    public ResponseEntity<CreateTenantResponse> create(@Valid @RequestBody CreateTenantRequest request,
                                                         @AuthenticationPrincipal AppPrincipal principal) {
        requireAuthenticated(principal);
        Tenant tenant = tenantService.createTenantOnly(request.name(), request.slug());
        // Two separate externally-invoked service calls (not one two-step internal method):
        // see TenantService#addMembership javadoc for why self-invocation would break @TenantScoped.
        TenantContext.setTenantId(tenant.getId());
        try {
            tenantService.addMembership(tenant.getId(), principal.userId(), MembershipRole.OWNER);
        } finally {
            TenantContext.clear();
        }
        String token = jwtService.generateToken(principal.userId(), principal.email(), tenant.getId(), MembershipRole.OWNER);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateTenantResponse(TenantResponse.from(tenant), token));
    }

    @GetMapping
    public List<MyTenantSummary> myTenants(@AuthenticationPrincipal AppPrincipal principal) {
        requireAuthenticated(principal);
        return tenantService.listMyTenants(principal.userId());
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('VIEWER')")
    @GetMapping("/{tenantId}")
    public TenantResponse get(@PathVariable UUID tenantId) {
        return tenantService.getTenant(tenantId);
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('ADMIN')")
    @PatchMapping("/{tenantId}/settings")
    public TenantResponse updateSettings(@PathVariable UUID tenantId, @Valid @RequestBody UpdateTenantSettingsRequest request) {
        return tenantService.updateSettings(tenantId, request.settings());
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('VIEWER')")
    @GetMapping("/{tenantId}/members")
    public List<MemberResponse> members(@PathVariable UUID tenantId) {
        return tenantService.listMembers(tenantId);
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('ADMIN')")
    @PostMapping("/{tenantId}/members")
    public ResponseEntity<MemberResponse> addMember(@PathVariable UUID tenantId, @Valid @RequestBody AddMemberRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(tenantService.addMemberByEmail(tenantId, request.email(), request.role()));
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('ADMIN')")
    @PatchMapping("/{tenantId}/members/{userId}/role")
    public void updateMemberRole(@PathVariable UUID tenantId, @PathVariable UUID userId,
                                  @Valid @RequestBody UpdateMemberRoleRequest request) {
        tenantService.updateMemberRole(tenantId, userId, request.role());
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('ADMIN')")
    @DeleteMapping("/{tenantId}/members/{userId}")
    public void removeMember(@PathVariable UUID tenantId, @PathVariable UUID userId) {
        tenantService.removeMember(tenantId, userId);
    }

    private void requireAuthenticated(AppPrincipal principal) {
        if (principal == null) {
            throw new ApiExceptions.UnauthorizedException("Authentication required");
        }
    }
}
