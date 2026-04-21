package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.UserSession;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.UserSessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Optional;

public class AuthTokenFilter extends OncePerRequestFilter {
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

                    // 1. Check Account Status
                    if ("LOCKED".equals(user.getAccountStatus())) {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Account is locked");
                        return;
                    }

                    // 2. Check Session Status
                    if (sessionId == null) {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid session token");
                        return;
                    }
                    Optional<UserSession> sessionOpt = sessionRepository.findByTokenId(sessionId);
                    if (sessionOpt.isEmpty() || !"ACTIVE".equals(sessionOpt.get().getStatus())) {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Session expired or revoked");
                        return;
                    }

                    // 3. Check IP Whitelist
                    if (user.getIpWhitelist() != null && !user.getIpWhitelist().isEmpty()) {
                        String currentIp = request.getRemoteAddr();
                        if (!user.getIpWhitelist().contains(currentIp)) {
                             response.sendError(HttpServletResponse.SC_FORBIDDEN, "IP not whitelisted");
                             return;
                        }
                    }

                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            email, null, new ArrayList<>());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (Exception e) {
            logger.error("Cannot set user authentication: " + e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private String parseJwt(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");
        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            return headerAuth.substring(7);
        }
        return null;
    }
}
