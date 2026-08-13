package com.docstructure.platform.auth;

import com.docstructure.platform.common.TenantContext;
import io.jsonwebtoken.Claims;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Populates SecurityContext + TenantContext from a Bearer JWT. Leaves the request
 * unauthenticated (no error) if the header is absent or invalid so GuestAuthFilter and the
 * anonymous/permitAll rules in SecurityConfig get a chance to handle the request instead.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
            // Fallback for the one case that genuinely can't set a header: browser EventSource
            // (used for live document-status updates, see DocumentEventService) has no API to
            // send Authorization, so the frontend passes the same JWT as ?token= instead. Only
            // relevant for that GET /documents/events endpoint in practice, but this is a plain
            // alternate transport for the identical token/validation, not a weaker auth path.
            if (token == null) {
                String queryToken = request.getParameter("token");
                if (queryToken != null && !queryToken.isBlank()) {
                    token = queryToken;
                }
            }
            if (token != null) {
                Optional<Claims> claims = jwtService.parse(token);
                claims.ifPresent(this::authenticate);
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void authenticate(Claims claims) {
        UUID userId = UUID.fromString(claims.getSubject());
        String email = claims.get("email", String.class);
        String tenantIdClaim = claims.get("tenantId", String.class);
        String roleClaim = claims.get("role", String.class);

        UUID tenantId = tenantIdClaim != null ? UUID.fromString(tenantIdClaim) : null;
        MembershipRole role = roleClaim != null ? MembershipRole.valueOf(roleClaim) : null;

        AppPrincipal principal = new AppPrincipal(userId, email, tenantId, role);
        List<GrantedAuthority> authorities = role != null
                ? List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
                : List.of();

        var authToken = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authToken);

        MDC.put("userId", userId.toString());
        if (tenantId != null) {
            TenantContext.setTenantId(tenantId);
            MDC.put("tenantId", tenantId.toString());
        }
    }
}
