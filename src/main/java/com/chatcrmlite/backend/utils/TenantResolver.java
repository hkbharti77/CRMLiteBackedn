package com.chatcrmlite.backend.utils;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class TenantResolver {

    private final UserRepository userRepository;

    /**
     * Resolves the active Tenant / Business ID for multi-tenant isolation.
     * Hierarchy:
     * 1. X-Tenant-ID HTTP Header (from frontend apiFetch)
     * 2. businessId query / request parameter
     * 3. Authenticated User's Tenant ID or User ID from Spring Security context
     * 4. Fallback to "default"
     */
    public String resolveBusinessId(String xTenantIdHeader, String queryBusinessId) {
        if (xTenantIdHeader != null && !xTenantIdHeader.isBlank() && !"default".equalsIgnoreCase(xTenantIdHeader)) {
            return xTenantIdHeader.trim();
        }
        if (queryBusinessId != null && !queryBusinessId.isBlank() && !"default".equalsIgnoreCase(queryBusinessId)) {
            return queryBusinessId.trim();
        }

        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof String email) {
                Optional<User> userOpt = userRepository.findByEmail(email);
                if (userOpt.isPresent()) {
                    User user = userOpt.get();
                    if (user.getTenant() != null && user.getTenant().getId() != null) {
                        return user.getTenant().getId().toString();
                    }
                    if (user.getId() != null) {
                        return user.getId().toString();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Tenant resolution fallback to default: {}", e.getMessage());
        }

        return "default";
    }
}
