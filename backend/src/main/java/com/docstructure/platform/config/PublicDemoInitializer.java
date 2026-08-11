package com.docstructure.platform.config;

import com.docstructure.platform.auth.User;
import com.docstructure.platform.auth.UserRepository;
import com.docstructure.platform.tenancy.Tenant;
import com.docstructure.platform.tenancy.TenantRepository;
import com.docstructure.platform.tenancy.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Seeds the one shared tenant (+ a reserved, unusable-login user to own the FK on uploaded
 * documents) that anonymous "try it before you subscribe" uploads land in — see
 * PublicDemoService. Unlike BootstrapDataInitializer this doesn't gate on "any user exists":
 * it checks for its own specific tenant slug, so it still seeds correctly on a database that
 * already has real tenants/users (bootstrap's own guard would otherwise skip it entirely).
 */
@Component
@Order(2)
public class PublicDemoInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PublicDemoInitializer.class);

    public static final String TENANT_SLUG = "public-demo";
    public static final String ANON_USER_EMAIL = "anonymous@public-demo.local";

    private final TenantRepository tenantRepository;
    private final TenantService tenantService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;

    public PublicDemoInitializer(TenantRepository tenantRepository, TenantService tenantService,
                                  UserRepository userRepository, PasswordEncoder passwordEncoder,
                                  @Value("${platform.public-demo.enabled}") boolean enabled) {
        this.tenantRepository = tenantRepository;
        this.tenantService = tenantService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
    }

    @Override
    public void run(String... args) {
        if (!enabled || tenantRepository.findBySlug(TENANT_SLUG).isPresent()) {
            return;
        }
        User anonUser = userRepository.findByEmail(ANON_USER_EMAIL).orElseGet(() -> {
            User u = new User();
            u.setEmail(ANON_USER_EMAIL);
            // Random, never handed to anyone — this account is only ever referenced as a
            // foreign key owner for anonymous uploads, never logged into.
            u.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
            u.setFullName("Anonymous (public demo)");
            return userRepository.save(u);
        });

        Tenant tenant = tenantService.createTenantOnly("Public Demo", TENANT_SLUG);
        log.warn("Bootstrapped public demo tenant '{}' (slug={}) for anonymous trial uploads, owned by '{}'.",
                tenant.getName(), tenant.getSlug(), anonUser.getEmail());
    }
}
