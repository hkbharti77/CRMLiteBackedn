package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.PermissionAuditLog;
import com.chatcrmlite.backend.models.PermissionKey;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.PermissionAuditLogRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AgentPermissionService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PermissionAuditLogRepository permissionAuditLogRepository;

    @Transactional
    public User updateAgentPermissions(User currentUser, UUID agentId, List<String> requestedPermissions, Integer expectedVersion, String reason, String ipAddress, String userAgent) {
        if (currentUser.getRole() != User.Role.OWNER && currentUser.getRole() != User.Role.ADMIN) {
            throw new org.springframework.security.access.AccessDeniedException("Only Tenant Owners and Admins can update agent permissions.");
        }

        User targetAgent = userRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent user not found with ID: " + agentId));

        // Enforce Strict Tenant Isolation
        if (currentUser.getTenant() == null || targetAgent.getTenant() == null ||
                !currentUser.getTenant().getId().equals(targetAgent.getTenant().getId())) {
            throw new org.springframework.security.access.AccessDeniedException("Cross-tenant permission mutation blocked.");
        }

        // Prevent Self-Edit & Agent-on-Agent Edit
        if (currentUser.getId().equals(targetAgent.getId())) {
            throw new org.springframework.security.access.AccessDeniedException("Users cannot modify their own permissions.");
        }

        if (targetAgent.getRole() != User.Role.AGENT) {
            throw new IllegalArgumentException("Permissions can only be explicitly assigned to AGENT role users.");
        }

        // Optimistic Locking Check
        if (expectedVersion != null && !expectedVersion.equals(targetAgent.getPermissionVersion())) {
            throw new ObjectOptimisticLockingFailureException(User.class, agentId);
        }

        // Validate and Normalize Requested Permissions
        List<String> normalizedPermissions = validateAndNormalizePermissions(requestedPermissions);

        List<String> oldPermissions = new ArrayList<>(targetAgent.getPermissions());

        // Update Target Agent
        targetAgent.setPermissions(normalizedPermissions);
        targetAgent.setPermissionVersion((targetAgent.getPermissionVersion() == null ? 1 : targetAgent.getPermissionVersion()) + 1);

        User savedAgent = userRepository.save(targetAgent);

        // Record Immutable Audit Log Entry
        PermissionAuditLog auditLog = new PermissionAuditLog(
                currentUser.getTenant().getId(),
                targetAgent.getId(),
                currentUser.getId(),
                "UPDATE_PERMISSIONS",
                oldPermissions,
                normalizedPermissions,
                reason,
                UUID.randomUUID().toString(),
                ipAddress,
                userAgent,
                savedAgent.getPermissionVersion()
        );
        permissionAuditLogRepository.save(auditLog);

        return savedAgent;
    }

    public List<String> validateAndNormalizePermissions(List<String> requestedPermissions) {
        if (requestedPermissions == null || requestedPermissions.isEmpty()) {
            return new ArrayList<>();
        }

        Set<PermissionKey> resolvedKeys = new HashSet<>();

        for (String rawKey : requestedPermissions) {
            Optional<PermissionKey> keyOpt = PermissionKey.fromString(rawKey);
            if (keyOpt.isEmpty()) {
                throw new IllegalArgumentException("Invalid permission key: " + rawKey);
            }
            PermissionKey key = keyOpt.get();

            // Reject Admin-Only permissions for agents
            if (key.isAdminOnly()) {
                throw new IllegalArgumentException("Permission " + key.name() + " is restricted to ADMIN/OWNER roles only.");
            }

            resolvedKeys.add(key);
        }

        // Enforce Parent Permission Hierarchy (SETTINGS_* require MODULE_SETTINGS)
        for (PermissionKey key : resolvedKeys) {
            if (key.getParentKey() != null && !resolvedKeys.contains(key.getParentKey())) {
                throw new IllegalArgumentException("Permission " + key.name() + " requires parent permission " + key.getParentKey().name() + " to be enabled.");
            }
        }

        return resolvedKeys.stream()
                .map(Enum::name)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PermissionAuditLog> getAuditLogsForAgent(User currentUser, UUID agentId) {
        if (currentUser.getRole() != User.Role.OWNER && currentUser.getRole() != User.Role.ADMIN) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied.");
        }
        return permissionAuditLogRepository.findByTenantIdAndAgentIdOrderByCreatedAtDesc(currentUser.getTenant().getId(), agentId);
    }
}
