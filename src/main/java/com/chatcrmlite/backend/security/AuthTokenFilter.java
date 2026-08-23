package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.UserSession;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.UserSessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * JWT authentication filter — runs once per request.
 *
 * Security hardening applied:
 * - Loads the user's Role from DB and maps it to a GrantedAuthority
 *   (ROLE_OWNER / ROLE_ADMIN / ROLE_AGENT) so @PreAuthorize works correctly.
 * - Account status check (LOCKED) before granting authentication.
 * - Session validation (ACTIVE status in DB) — invalidated sessions are rejected.
 * - IP whitelist enforcement if the user has one configured.
 * - Exception strings do NOT expose JWT content or user data.
 * - MDC is cleared in finally to prevent tenant context leaking between requests.
 */
public class AuthTokenFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AuthTokenFilter.class);

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserSessionRepository sessionRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String jwt = parseJwt(request);
            if (jwt != null && jwtUtils.validateJwtToken(jwt)) {
                String email = jwtUtils.getEmailFromJwtToken(jwt);
                String sessionId = jwtUtils.getSessionIdFromJwtToken(jwt);

                Optional<User> userOpt = userRepository.findByEmail(email);
                if (userOpt.isPresent()) {
                    User user = userOpt.get();

                    if (user.getTenant() != null && user.getTenant().getId() != null) {
                        TenantContext.setTenantId(user.getTenant().getId());
                        MDC.put("tenantId", user.getTenant().getId().toString());
                    }
                    if (email != null) {
                        MDC.put("userEmail", email);  // available in log patterns
                    }

                    if (user.getAccountStatus() == User.AccountStatus.LOCKED) {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Account is locked");
                        return;
                    }

                    if (sessionId == null) {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid session token");
                        return;
                    }
                    Optional<UserSession> sessionOpt = sessionRepository.findByTokenId(sessionId);
                    if (sessionOpt.isEmpty() || !"ACTIVE".equals(sessionOpt.get().getStatus())) {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Session expired or revoked");
                        return;
                    }

                    if (user.getIpWhitelist() != null && !user.getIpWhitelist().isEmpty()) {
                        String currentIp = getClientIp(request);
                        if (!user.getIpWhitelist().contains(currentIp)) {
                            log.warn("[Security] IP whitelist rejection for user email=REDACTED, ip={}", currentIp);
                            response.sendError(HttpServletResponse.SC_FORBIDDEN, "IP not whitelisted");
                            return;
                        }
                    }

                    // Build GrantedAuthority from the user's persisted role.
                    // This feeds @PreAuthorize("hasRole('ADMIN')") etc.
                    String roleAuthority = "ROLE_" + (user.getRole() != null ? user.getRole().name() : "OWNER");
                    List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(roleAuthority));

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(email, null, authorities);
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.debug("[Auth] Request authenticated — role={}", roleAuthority);
                }
            }

            filterChain.doFilter(request, response);
        } catch (Exception e) {
            // Log problem class, not the token value
            log.error("[Auth] Authentication processing failed: {}", e.getClass().getSimpleName());
            if (!response.isCommitted()) {
                filterChain.doFilter(request, response);
            }
        } finally {
            TenantContext.clear();
            MDC.remove("tenantId");
            MDC.remove("userEmail");
        }
    }

    private String parseJwt(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");
        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            return headerAuth.substring(7);
        }
        String paramToken = request.getParameter("access_token");
        if (!StringUtils.hasText(paramToken)) {
            paramToken = request.getParameter("token");
        }
        if (StringUtils.hasText(paramToken)) {
            return paramToken;
        }
        return null;
    }

    /**
     * Resolves the real client IP, respecting X-Forwarded-For when behind a trusted proxy.
     * Only use the first IP in the chain (the original client).
     */
    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
