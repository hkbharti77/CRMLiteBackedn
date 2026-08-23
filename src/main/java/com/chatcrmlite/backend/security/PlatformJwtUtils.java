package com.chatcrmlite.backend.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.Map;

/**
 * JWT utility for the Platform Owner (Super Admin) panel.
 *
 * Produces tokens with an extra claim: "platform": true
 * This claim is checked by PlatformAuthFilter to distinguish platform tokens
 * from tenant user tokens — preventing cross-contamination between the two
 * auth systems.
 *
 * Uses the same JWT_SECRET as the tenant system (simpler config) but
 * a separate 8-hour expiry and distinct claim signature.
 */
@Component
public class PlatformJwtUtils {

    private static final Logger log = LoggerFactory.getLogger(PlatformJwtUtils.class);
    private static final int MIN_SECRET_BYTES = 32;
    private static final long PLATFORM_TOKEN_EXPIRY_MS = 8L * 60 * 60 * 1000; // 8 hours

    @Value("${jwt.secret:dummy_jwt_secret_key_for_testing_min_64_bytes_long}")
    private String jwtSecret;

    private Key signingKey;

    @PostConstruct
    public void init() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                "[Platform] JWT_SECRET too short. Minimum " + MIN_SECRET_BYTES + " bytes required."
            );
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("[Platform] JWT signing key initialised");
    }

    /** Generates a platform JWT with the "platform": true marker claim. */
    public String generatePlatformToken(String email) {
        return Jwts.builder()
            .setSubject(email)
            .addClaims(Map.of("platform", true))
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + PLATFORM_TOKEN_EXPIRY_MS))
            .signWith(signingKey)
            .compact();
    }

    public String getEmailFromToken(String token) {
        return getClaims(token).getSubject();
    }

    /** Returns true ONLY if token is valid AND contains the "platform": true claim. */
    public boolean validatePlatformToken(String token) {
        try {
            Claims claims = getClaims(token);
            Object platformClaim = claims.get("platform");
            if (!Boolean.TRUE.equals(platformClaim)) {
                log.debug("[Platform] JWT missing 'platform' claim — checking standard user token fallback");
                return false;
            }
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("[Platform] Token expired");
        } catch (JwtException e) {
            log.warn("[Platform] JWT validation failed: {}", e.getClass().getSimpleName());
        }
        return false;
    }

    private Claims getClaims(String token) {
        return Jwts.parserBuilder()
            .setSigningKey(signingKey)
            .build()
            .parseClaimsJws(token)
            .getBody();
    }
}
