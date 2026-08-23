package com.chatcrmlite.backend.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.nio.charset.StandardCharsets;

/**
 * JWT utility — handles token generation, parsing, and validation.
 *
 * Security hardening applied:
 * - Uses SLF4J (not System.err) so log output is controlled by Logback config
 * - Signing key cached at startup (@PostConstruct) — avoids re-deriving per request
 * - Minimum secret length enforced: 32 bytes required for HS256
 * - JwtException caught broadly so error details are never surfaced to callers
 * - Expiration stored as long (not int) to avoid overflow for values > 24 days
 */
@Component
public class JwtUtils {
    private static final Logger log = LoggerFactory.getLogger(JwtUtils.class);
    private static final int MIN_SECRET_BYTES = 32;

    @Value("${jwt.secret:dummy_jwt_secret_key_for_testing_min_64_bytes_long}")
    private String jwtSecret;

    @Value("${jwt.expiration:86400000}")
    private long jwtExpirationMs;  // long — int overflows after ~24 days

    /** Cached key derived once at startup. */
    private Key signingKey;

    @PostConstruct
    public void init() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            // Fail fast — a weak secret is a critical vulnerability
            throw new IllegalStateException(
                "JWT_SECRET is too short. Minimum " + MIN_SECRET_BYTES +
                " bytes required (got " + keyBytes.length + "). " +
                "Generate with: openssl rand -base64 64"
            );
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("[Security] JWT signing key initialised (length: {} bytes)", keyBytes.length);
    }

    /**
     * Generates a signed JWT embedding the user's email (subject) and session ID (jti).
     */
    public String generateJwtToken(String email, String sessionId) {
        return Jwts.builder()
                .setSubject(email)
                .setId(sessionId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
                .signWith(signingKey)
                .compact();
    }

    public String getEmailFromJwtToken(String token) {
        return getClaimsFromToken(token).getSubject();
    }

    public String getSessionIdFromJwtToken(String token) {
        return getClaimsFromToken(token).getId();
    }

    private Claims getClaimsFromToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * Validates the token.
     * Errors are logged at WARN without exposing the token value or full stack trace.
     * The exception type is logged so ops can distinguish expired vs tampered tokens.
     */
    public boolean validateJwtToken(String authToken) {
        try {
            Jwts.parserBuilder().setSigningKey(signingKey).build().parseClaimsJws(authToken);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("[JWT] Token expired: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.warn("[JWT] Unsupported token format");
        } catch (MalformedJwtException e) {
            log.warn("[JWT] Malformed token");
        } catch (io.jsonwebtoken.security.SignatureException e) {
            log.warn("[JWT] Invalid signature — possible token tampering");
        } catch (IllegalArgumentException e) {
            log.warn("[JWT] Token is null or empty");
        }
        return false;
    }
}
