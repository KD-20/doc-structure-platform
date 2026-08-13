package com.docstructure.platform.tenancy;

import com.docstructure.platform.auth.MembershipRole;
import com.docstructure.platform.auth.TenantMembership;
import com.docstructure.platform.auth.TenantMembershipRepository;
import com.docstructure.platform.auth.TenantMembershipRow;
import com.docstructure.platform.auth.User;
import com.docstructure.platform.auth.UserRepository;
import com.docstructure.platform.audit.Audited;
import com.docstructure.platform.common.ApiExceptions;
import com.docstructure.platform.common.TenantScoped;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);

    private final TenantRepository tenantRepository;
    private final TenantMembershipRepository membershipRepository;
    private final UserRepository userRepository;

    public TenantService(TenantRepository tenantRepository, TenantMembershipRepository membershipRepository,
                          UserRepository userRepository) {
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
    }

    /**
     * Just the tenant row (tenants has no RLS, so no @TenantScoped needed). The caller
     * (TenantController) adds the creator's OWNER membership as a second, separate call so
     * that step can run under @TenantScoped once the new tenant's id is known — see
     * TenantController#create for why this can't be one internal two-step service method
     * (Spring AOP doesn't intercept self-invocation, i.e. this.otherMethod()).
     */
    @Transactional
    public Tenant createTenantOnly(String name, String slug) {
        if (tenantRepository.findBySlug(slug).isPresent()) {
            throw new ApiExceptions.ConflictException("Slug '" + slug + "' is already taken");
        }
        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant.setSlug(slug);
        tenant = tenantRepository.save(tenant);
        log.info("tenant created id={} slug={}", tenant.getId(), slug);
        return tenant;
    }

    @TenantScoped
    @Transactional
    public void addMembership(UUID tenantId, UUID userId, MembershipRole role) {
        if (membershipRepository.findByTenantIdAndUserId(tenantId, userId).isPresent()) {
            throw new ApiExceptions.ConflictException("User is already a member of this tenant");
        }
        TenantMembership membership = new TenantMembership();
        membership.setTenantId(tenantId);
        membership.setUserId(userId);
        membership.setRole(role);
        membershipRepository.save(membership);
        log.info("membership added tenant={} user={} role={}", tenantId, userId, role);
    }

    @Transactional(readOnly = true)
    public List<MyTenantSummary> listMyTenants(UUID userId) {
        List<TenantMembershipRow> memberships = membershipRepository.listAllForUser(userId);
        Map<UUID, Tenant> tenantsById = new HashMap<>();
        tenantRepository.findAllById(memberships.stream().map(TenantMembershipRow::getTenantId).toList())
                .forEach(t -> tenantsById.put(t.getId(), t));
        return memberships.stream()
                .map(m -> new MyTenantSummary(
                        m.getTenantId(),
                        tenantsById.containsKey(m.getTenantId()) ? tenantsById.get(m.getTenantId()).getName() : "Unknown",
                        MembershipRole.valueOf(m.getRole())))
                .toList();
    }

    @Transactional(readOnly = true)
    public TenantResponse getTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .map(TenantResponse::from)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Tenant not found"));
    }

    @Transactional
    public TenantResponse updateSettings(UUID tenantId, JsonNode settings) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Tenant not found"));
        tenant.setSettings(settings);
        TenantResponse response = TenantResponse.from(tenantRepository.save(tenant));
        log.info("tenant settings updated id={}", tenantId);
        return response;
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public List<MemberResponse> listMembers(UUID tenantId) {
        List<TenantMembership> memberships = membershipRepository.findByTenantId(tenantId);
        Map<UUID, User> usersById = new HashMap<>();
        userRepository.findAllById(memberships.stream().map(TenantMembership::getUserId).toList())
                .forEach(u -> usersById.put(u.getId(), u));
        return memberships.stream()
                .map(m -> {
                    User u = usersById.get(m.getUserId());
                    return new MemberResponse(m.getUserId(), u != null ? u.getEmail() : "unknown",
                            u != null ? u.getFullName() : "Unknown", m.getRole());
                })
                .toList();
    }

    @TenantScoped
    @Transactional
    public MemberResponse addMemberByEmail(UUID tenantId, String email, MembershipRole role) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No user with email " + email));
        if (membershipRepository.findByTenantIdAndUserId(tenantId, user.getId()).isPresent()) {
            throw new ApiExceptions.ConflictException("User is already a member of this tenant");
        }
        TenantMembership membership = new TenantMembership();
        membership.setTenantId(tenantId);
        membership.setUserId(user.getId());
        membership.setRole(role);
        membershipRepository.save(membership);
        log.info("member added tenant={} user={} role={}", tenantId, user.getId(), role);
        return new MemberResponse(user.getId(), user.getEmail(), user.getFullName(), role);
    }

    @TenantScoped
    @Transactional
    @Audited(action = "TENANT_MEMBER_ROLE_CHANGED", entityType = "TENANT_MEMBERSHIP", entityIdArgIndex = 1)
    public void updateMemberRole(UUID tenantId, UUID userId, MembershipRole newRole) {
        TenantMembership membership = membershipRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Membership not found"));
        if (membership.getRole() == MembershipRole.OWNER && newRole != MembershipRole.OWNER
                && countOwners(tenantId) <= 1) {
            log.warn("rejected demoting last owner tenant={} user={}", tenantId, userId);
            throw new ApiExceptions.ValidationException("Cannot demote the last remaining owner");
        }
        MembershipRole previousRole = membership.getRole();
        membership.setRole(newRole);
        membershipRepository.save(membership);
        log.info("member role changed tenant={} user={} from={} to={}", tenantId, userId, previousRole, newRole);
    }

    @TenantScoped
    @Transactional
    public void removeMember(UUID tenantId, UUID userId) {
        TenantMembership membership = membershipRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Membership not found"));
        if (membership.getRole() == MembershipRole.OWNER && countOwners(tenantId) <= 1) {
            log.warn("rejected removing last owner tenant={} user={}", tenantId, userId);
            throw new ApiExceptions.ValidationException("Cannot remove the last remaining owner");
        }
        membershipRepository.delete(membership);
        log.info("member removed tenant={} user={}", tenantId, userId);
    }

    private long countOwners(UUID tenantId) {
        return membershipRepository.findByTenantId(tenantId).stream()
                .filter(m -> m.getRole() == MembershipRole.OWNER)
                .count();
    }
}
