package com.docstructure.platform.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Outermost filter (registered before JwtAuthFilter/GuestAuthFilter in SecurityConfig) — wraps
 * the entire chain so its own finally block is the last thing to run per request, making it the
 * right place for the one MDC.clear() that cleans up everything auth filters add downstream
 * (tenantId, userId, guestLinkId — see JwtAuthFilter/GuestAuthFilter), not just requestId.
 * Also doubles as a minimal structured access log (method, path, status, duration) — see
 * logback-spring.xml for how MDC fields end up in each JSON log line.
 */
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("access");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        long startedAt = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - startedAt;
            log.info("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(), response.getStatus(),
                    durationMs);
            MDC.clear();
        }
    }
}
