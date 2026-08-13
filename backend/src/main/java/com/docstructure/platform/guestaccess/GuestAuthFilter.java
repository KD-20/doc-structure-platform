package com.docstructure.platform.guestaccess;

import com.docstructure.platform.common.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Authenticates a request from an opaque bearer-style token (X-Guest-Token header, or
 * ?guestToken= for links opened directly in a browser) instead of a JWT. Registered after
 * JwtAuthFilter in SecurityConfig and skips entirely if that filter already authenticated the
 * request, so a real user's session is never overridden by a stray guest token.
 */
public class GuestAuthFilter extends OncePerRequestFilter {

    private final GuestLinkService guestLinkService;

    public GuestAuthFilter(GuestLinkService guestLinkService) {
        this.guestLinkService = guestLinkService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                String token = request.getHeader("X-Guest-Token");
                if (token == null || token.isBlank()) {
                    token = request.getParameter("guestToken");
                }
                if (token != null && !token.isBlank()) {
                    authenticate(token);
                }
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void authenticate(String rawToken) {
        guestLinkService.findUsableByToken(rawToken).ifPresent(link -> {
            List<UUID> documentIds = guestLinkService.extractDocumentIds(link.getScope());
            GuestPrincipal principal = new GuestPrincipal(link.getId(), link.getTenantId(), documentIds);
            List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_GUEST"));
            var authToken = new UsernamePasswordAuthenticationToken(principal, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authToken);
            TenantContext.setTenantId(link.getTenantId());
            MDC.put("tenantId", link.getTenantId().toString());
            MDC.put("guestLinkId", link.getId().toString());
            guestLinkService.recordUse(link.getTenantId(), link.getId());
        });
    }
}
