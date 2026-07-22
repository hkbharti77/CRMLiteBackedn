package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.repositories.PlatformAdminRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * JWT filter for the /api/v1/platform/** route namespace.
 *
 * Security design:
 * - Reads token from HttpOnly cookie "platform_token" (NOT Authorization header).
 *   This prevents XSS-based token theft since JS cannot read HttpOnly cookies.
 * - Validates the "platform": true claim so tenant tokens cannot access platform routes.
 * - Only authenticates against platform_admin table, never against app_users.
 * - Sets principal as the admin email with ROLE_PLATFORM_ADMIN authority.
 */
public class PlatformAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PlatformAuthFilter.class);
    private static final String PLATFORM_COOKIE = "platform_token";

    @Autowired
    private PlatformJwtUtils platformJwtUtils;

    @Autowired
    private PlatformAdminRepository platformAdminRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        try {
            String token = extractCookieToken(request);

            if (token != null && platformJwtUtils.validatePlatformToken(token)) {
                String email = platformJwtUtils.getEmailFromToken(token);

                // Verify admin exists in DB
                boolean adminExists = platformAdminRepository.findByEmailIgnoreCase(email).isPresent();
                if (adminExists) {
                    List<SimpleGrantedAuthority> authorities =
                        List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));

                    UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(email, null, authorities);
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);

                    // CRITICAL: Mark this thread as admin-mode so TenantFilterAspect
                    // bypasses the Hibernate tenant filter for all repository calls.
                    // Without this, every repository.findAll() on a BaseTenantEntity
                    // subclass fails because there is no tenantId in context.
                    TenantContext.setAdminMode(true);

                    log.debug("[Platform] Request authenticated for REDACTED — admin mode enabled");
                } else {
                    log.warn("[Platform] Token valid but admin not found in DB — possible stale token");
                }
            }
        } catch (Exception e) {
            log.error("[Platform] Auth filter error: {}", e.getClass().getSimpleName());
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Always clean up ThreadLocal state to prevent leaks in thread pools.
            TenantContext.clear();
        }
    }

    private String extractCookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies)
            .filter(c -> PLATFORM_COOKIE.equals(c.getName()))
            .map(Cookie::getValue)
            .findFirst()
            .orElse(null);
    }

    /** Resolve real client IP (respects X-Forwarded-For). */
    public static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
