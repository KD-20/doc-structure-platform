package com.docstructure.platform.auth;

import com.docstructure.platform.common.ApiExceptions;
import com.docstructure.platform.common.TenantScoped;
import com.docstructure.platform.tenancy.Tenant;
import com.docstructure.platform.tenancy.TenantRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final TenantMembershipRepository membershipRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, TenantMembershipRepository membershipRepository,
                        TenantRepository tenantRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public User register(String email, String rawPassword, String fullName) {
        if (userRepository.existsByEmail(email)) {
            throw new ApiExceptions.ConflictException("An account with this email already exists");
        }
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setFullName(fullName);
        return userRepository.save(user);
    }

    /** Identity-only token (no tenant claim) + every tenant the user belongs to, so the client can offer a picker. */
    @Transactional(readOnly = true)
    public LoginResponse login(String email, String rawPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiExceptions.UnauthorizedException("Invalid email or password"));
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new ApiExceptions.UnauthorizedException("Invalid email or password");
        }

        List<TenantMembershipRow> memberships = membershipRepository.listAllForUser(user.getId());
        Map<UUID, Tenant> tenantsById = new HashMap<>();
        tenantRepository.findAllById(memberships.stream().map(TenantMembershipRow::getTenantId).toList())
                .forEach(t -> tenantsById.put(t.getId(), t));

        List<TenantMembershipSummary> summaries = memberships.stream()
                .map(m -> new TenantMembershipSummary(
                        m.getTenantId(),
                        tenantsById.containsKey(m.getTenantId()) ? tenantsById.get(m.getTenantId()).getName() : "Unknown",
                        MembershipRole.valueOf(m.getRole())))
                .toList();

        String token = jwtService.generateToken(user.getId(), user.getEmail(), null, null);
        return new LoginResponse(token, summaries);
    }

    /**
     * Verifies membership in the REQUESTED tenant, which is exactly what RLS needs a tenant
     * context for — but the incoming identity-only token has no tenant claim of its own, so
     * the CALLER must call TenantContext.setTenantId(tenantId) before invoking this method
     * (TenantContextAspect reads TenantContext at the start of its @Around advice, i.e.
     * before this method body runs, so setting it inside the body would be one step too
     * late). See AuthController#selectTenant.
     *
     * @TenantScoped/@Transactional must sit on THIS method, not a private helper it calls:
     * Spring AOP only intercepts calls that go through the proxy, so a self-invoked
     * (this.foo()) call to an annotated helper would silently skip both aspects.
     */
    @TenantScoped
    @Transactional(readOnly = true)
    public SelectTenantResponse selectTenant(UUID userId, UUID tenantId) {
        MembershipRole role = membershipRepository.findByTenantIdAndUserId(tenantId, userId)
                .map(TenantMembership::getRole)
                .orElseThrow(() -> new ApiExceptions.ForbiddenException("Not a member of this tenant"));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiExceptions.UnauthorizedException("User no longer exists"));
        String token = jwtService.generateToken(userId, user.getEmail(), tenantId, role);
        return new SelectTenantResponse(token, tenantId, role);
    }
}
