package com.docstructure.platform.auth;

import com.docstructure.platform.common.ApiExceptions;
import com.docstructure.platform.common.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request.email(), request.password(), request.fullName());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @PostMapping("/select-tenant/{tenantId}")
    public SelectTenantResponse selectTenant(@PathVariable UUID tenantId, @AuthenticationPrincipal AppPrincipal principal) {
        if (principal == null) {
            throw new ApiExceptions.UnauthorizedException("Authentication required");
        }
        // See AuthService#selectTenant: it needs TenantContext set to the tenant being
        // verified BEFORE the call, since the incoming token has no tenant of its own yet.
        TenantContext.setTenantId(tenantId);
        try {
            return authService.selectTenant(principal.userId(), tenantId);
        } finally {
            TenantContext.clear();
        }
    }

    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal AppPrincipal principal) {
        if (principal == null) {
            throw new ApiExceptions.UnauthorizedException("Authentication required");
        }
        return new CurrentUserResponse(principal.userId(), principal.email(), principal.tenantId(), principal.role());
    }
}
