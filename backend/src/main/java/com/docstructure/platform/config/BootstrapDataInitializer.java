package com.docstructure.platform.config;

import com.docstructure.platform.auth.MembershipRole;
import com.docstructure.platform.auth.User;
import com.docstructure.platform.auth.UserRepository;
import com.docstructure.platform.common.TenantContext;
import com.docstructure.platform.tenancy.Tenant;
import com.docstructure.platform.tenancy.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds one demo tenant + admin user on first boot against an empty database, so
 * `docker compose up --build` produces something immediately usable without a separate
 * setup step. Skips silently if any user already exists (idempotent across restarts) or if
 * disabled via platform.bootstrap.enabled=false.
 */
@Component
public class BootstrapDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapDataInitializer.class);

    private final UserRepository userRepository;
    private final TenantService tenantService;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String adminEmail;
    private final String adminPassword;
    private final String tenantName;
    private final String tenantSlug;

    public BootstrapDataInitializer(UserRepository userRepository, TenantService tenantService,
                                     PasswordEncoder passwordEncoder,
                                     @Value("${platform.bootstrap.enabled}") boolean enabled,
                                     @Value("${platform.bootstrap.admin-email}") String adminEmail,
                                     @Value("${platform.bootstrap.admin-password}") String adminPassword,
                                     @Value("${platform.bootstrap.tenant-name}") String tenantName,
                                     @Value("${platform.bootstrap.tenant-slug}") String tenantSlug) {
        this.userRepository = userRepository;
        this.tenantService = tenantService;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.tenantName = tenantName;
        this.tenantSlug = tenantSlug;
    }

    @Override
    public void run(String... args) {
        if (!enabled || userRepository.count() > 0) {
            return;
        }
        User admin = new User();
        admin.setEmail(adminEmail);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setFullName("Admin");
        admin = userRepository.save(admin);

        Tenant tenant = tenantService.createTenantOnly(tenantName, tenantSlug);
        TenantContext.setTenantId(tenant.getId());
        try {
            tenantService.addMembership(tenant.getId(), admin.getId(), MembershipRole.OWNER);
        } finally {
            TenantContext.clear();
        }
        log.warn("Bootstrapped demo tenant '{}' (slug={}) with admin user '{}'. "
                        + "These are DEV-ONLY credentials — change them before any non-local use.",
                tenant.getName(), tenant.getSlug(), admin.getEmail());
    }
}
