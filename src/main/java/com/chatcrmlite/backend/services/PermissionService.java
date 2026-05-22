package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.Permission;
import com.chatcrmlite.backend.models.RolePermissionConfig;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.RolePermissionConfigRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class PermissionService {
    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);
    
    private final UserRepository userRepository;
    private final RolePermissionConfigRepository configRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean hasPermission(String email, Permission requiredPermission) {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return false;
        }

        User user = userOpt.get();
        
        // 1. OWNER has full master rights automatically
        if (user.getRole() == User.Role.OWNER) {
            return true;
        }

        // 2. Fetch active assigned permissions for the user
        Set<Permission> assigned = getAssignedPermissions(user);

        // 3. Evaluate matching hierarchy (Enhancement #8)
        for (Permission p : assigned) {
            if (p.getImpliedPermissions().contains(requiredPermission)) {
                return true;
            }
        }

        log.warn("[RBAC] Access denied for user={} demanding permission={}", email, requiredPermission);
        return false;
    }

    private Set<Permission> getAssignedPermissions(User user) {
        UUID tenantId = user.getTenant().getId();
        User.Role role = user.getRole();

        // Check for customized settings first
        Optional<RolePermissionConfig> configOpt = configRepository.findByTenantIdAndRole(tenantId, role);
        if (configOpt.isPresent()) {
            try {
                List<String> list = objectMapper.readValue(configOpt.get().getPermissions(), new TypeReference<List<String>>() {});
                Set<Permission> set = new HashSet<>();
                for (String s : list) {
                    try {
                        set.add(Permission.valueOf(s));
                    } catch (IllegalArgumentException e) {
                        // ignore unknown permission keys safely
                    }
                }
                return set;
            } catch (Exception e) {
                log.error("[RBAC] Failed parsing custom permissions for tenant={}: {}", tenantId, e.getMessage());
            }
        }

        // Default Fallbacks
        Set<Permission> defaults = new HashSet<>();
        if (role == User.Role.ADMIN) {
            defaults.addAll(Arrays.asList(Permission.MANAGE_LEADS, Permission.MANAGE_TICKETS));
        } else if (role == User.Role.AGENT) {
            defaults.addAll(Arrays.asList(Permission.VIEW_LEADS, Permission.VIEW_TICKETS));
        }
        return defaults;
    }
}
