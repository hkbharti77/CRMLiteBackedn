package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.PermissionAuditLog;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.PermissionAuditLogRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AgentPermissionServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PermissionAuditLogRepository permissionAuditLogRepository;

    @InjectMocks
    private AgentPermissionService agentPermissionService;

    private Tenant tenantA;
    private Tenant tenantB;
    private User adminTenantA;
    private User agentTenantA;
    private User agentTenantB;
    private UUID agentIdA;

    @BeforeEach
    void setUp() {
        tenantA = new Tenant();
        tenantA.setId(UUID.randomUUID());

        tenantB = new Tenant();
        tenantB.setId(UUID.randomUUID());

        adminTenantA = new User();
        adminTenantA.setId(UUID.randomUUID());
        adminTenantA.setRole(User.Role.ADMIN);
        adminTenantA.setTenant(tenantA);

        agentIdA = UUID.randomUUID();
        agentTenantA = new User();
        agentTenantA.setId(agentIdA);
        agentTenantA.setRole(User.Role.AGENT);
        agentTenantA.setTenant(tenantA);
        agentTenantA.setPermissions(List.of("MODULE_INBOX"));
        agentTenantA.setPermissionVersion(1);

        agentTenantB = new User();
        agentTenantB.setId(UUID.randomUUID());
        agentTenantB.setRole(User.Role.AGENT);
        agentTenantB.setTenant(tenantB);
    }

    @Test
    @DisplayName("Admin can update permissions for agent in same tenant")
    void update_agent_permissions_success() {
        when(userRepository.findById(agentIdA)).thenReturn(Optional.of(agentTenantA));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        List<String> newPerms = List.of("MODULE_INBOX", "MODULE_LEADS", "MODULE_SETTINGS", "SETTINGS_PROFILE");
        User result = agentPermissionService.updateAgentPermissions(adminTenantA, agentIdA, newPerms, 1, "Reason", "127.0.0.1", "Agent");

        assertNotNull(result);
        assertEquals(2, result.getPermissionVersion());
        assertTrue(result.getPermissions().contains("MODULE_LEADS"));
        verify(permissionAuditLogRepository, times(1)).save(any(PermissionAuditLog.class));
    }

    @Test
    @DisplayName("Cross tenant permission mutation attack should be blocked")
    void cross_tenant_permission_mutation_blocked() {
        when(userRepository.findById(agentTenantB.getId())).thenReturn(Optional.of(agentTenantB));

        assertThrows(AccessDeniedException.class, () ->
            agentPermissionService.updateAgentPermissions(adminTenantA, agentTenantB.getId(), List.of("MODULE_INBOX"), 1, "Attack", "127.0.0.1", "Agent")
        );
    }

    @Test
    @DisplayName("Self permission edit by user should be blocked")
    void self_permission_edit_blocked() {
        when(userRepository.findById(adminTenantA.getId())).thenReturn(Optional.of(adminTenantA));

        assertThrows(AccessDeniedException.class, () ->
            agentPermissionService.updateAgentPermissions(adminTenantA, adminTenantA.getId(), List.of("MODULE_INBOX"), 1, "Self Edit", "127.0.0.1", "Agent")
        );
    }

    @Test
    @DisplayName("Granting admin-only permission to Agent should be rejected")
    void granting_admin_only_permission_rejected() {
        when(userRepository.findById(agentIdA)).thenReturn(Optional.of(agentTenantA));

        List<String> invalidPerms = List.of("MODULE_TEAM"); // Admin only!
        assertThrows(IllegalArgumentException.class, () ->
            agentPermissionService.updateAgentPermissions(adminTenantA, agentIdA, invalidPerms, 1, "Reason", "127.0.0.1", "Agent")
        );
    }

    @Test
    @DisplayName("Granting child permission without parent should be rejected")
    void child_without_parent_rejected() {
        when(userRepository.findById(agentIdA)).thenReturn(Optional.of(agentTenantA));

        List<String> orphanChildPerm = List.of("SETTINGS_WHATSAPP"); // Requires MODULE_SETTINGS
        assertThrows(IllegalArgumentException.class, () ->
            agentPermissionService.updateAgentPermissions(adminTenantA, agentIdA, orphanChildPerm, 1, "Reason", "127.0.0.1", "Agent")
        );
    }

    @Test
    @DisplayName("Stale version update should throw OptimisticLockingFailureException")
    void stale_version_collision_throws() {
        when(userRepository.findById(agentIdA)).thenReturn(Optional.of(agentTenantA));

        List<String> newPerms = List.of("MODULE_INBOX");
        assertThrows(ObjectOptimisticLockingFailureException.class, () ->
            agentPermissionService.updateAgentPermissions(adminTenantA, agentIdA, newPerms, 999, "Stale", "127.0.0.1", "Agent")
        );
    }
}
