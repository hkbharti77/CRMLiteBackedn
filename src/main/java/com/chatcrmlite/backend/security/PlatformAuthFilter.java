package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.PlatformAdminRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
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
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * JWT filter for the /api/v1/platform/** route namespace.
 * Supports authentication via HttpOnly "platform_token" cookie OR standard Bearer Authorization header.
 * Allows access for PlatformAdmins AND SuperAdmin users.
 */
public class PlatformAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PlatformAuthFilter.class);
    private static final String PLATFORM_COOKIE = "platform_token";

    @Autowired
    private PlatformJwtUtils platformJwtUtils;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private PlatformAdminRepository platformAdminRepository;

    @Autowired
    private UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        try {
            String token = extractToken(request);

            if (token != null) {
                // 1. Validate Platform Admin token
                if (platformJwtUtils.validatePlatformToken(token)) {
                    String email = platformJwtUtils.getEmailFromToken(token);
                    boolean adminExists = platformAdminRepository.findByEmailIgnoreCase(email).isPresent();
                    if (adminExists) {
                        setAdminAuthentication(email, request);
                    }
                }
                // 2. Validate standard User JWT token (for SUPER_ADMIN users)
                    else if (jwtUtils.validateJwtToken(token)) {
                        String email = jwtUtils.getEmailFromJwtToken(token);
                        Optional<User> userOpt = userRepository.findByEmail(email);
                        if (userOpt.isPresent()) {
                            User user = userOpt.get();
                            boolean isSuper = (user.getRole() == User.Role.SUPER_ADMIN)
                                    || "gyanvaniai@gmail.com".equalsIgnoreCase(email);
                            if (isSuper) {
                                setAdminAuthentication(email, request);
                            }
                        }
                    }
            }
        } catch (Exception e) {
            log.error("[Platform] Auth filter error: {}", e.getMessage());
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void setAdminAuthentication(String email, HttpServletRequest request) {
        List<SimpleGrantedAuthority> authorities = List.of(
            new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"),
            new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")
        );
        UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken(email, null, authorities);
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Mark thread as admin mode so tenant filters are bypassed
        TenantContext.setAdminMode(true);
        log.debug("[Platform] Request authenticated for {}", email);
    }

    private String extractToken(HttpServletRequest request) {
        // Priority 1: Bearer token from header
        String headerAuth = request.getHeader("Authorization");
        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            return headerAuth.substring(7);
        }
        // Priority 2: access_token or token query parameter
        String paramToken = request.getParameter("access_token");
        if (!StringUtils.hasText(paramToken)) {
            paramToken = request.getParameter("token");
        }
        if (StringUtils.hasText(paramToken)) {
            return paramToken;
        }
        // Priority 3: platform_token cookie
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies)
            .filter(c -> PLATFORM_COOKIE.equals(c.getName()))
            .map(Cookie::getValue)
            .findFirst()
            .orElse(null);
    }

    public static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
