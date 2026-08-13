package com.docstructure.platform.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and validates access tokens. A token minted at login (before any tenant is chosen)
 * carries no tenantId/role; select-tenant re-issues a token scoped to exactly one tenant.
 * Keeping every downstream authorization check single-tenant this way avoids needing to
 * parse/trust a multi-tenant membership list on every request.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final long accessTokenMinutes;

    public JwtService(@Value("${platform.jwt.secret}") String secret,
                       @Value("${platform.jwt.access-token-minutes}") long accessTokenMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenMinutes = accessTokenMinutes;
    }

    public String generateToken(UUID userId, String email, UUID tenantId, MembershipRole role) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenMinutes, ChronoUnit.MINUTES)));
        if (tenantId != null) {
            builder.claim("tenantId", tenantId.toString());
        }
        if (role != null) {
            builder.claim("role", role.name());
        }
        return builder.signWith(key).compact();
    }

    public Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            // Never log the token itself — just that one was rejected and why (expired,
            // bad signature, malformed, ...), which is enough to spot abuse/clock-skew patterns.
            log.debug("rejected JWT: {}", e.toString());
            return Optional.empty();
        }
    }
}
