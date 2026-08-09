package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO;
import com.chatcrmlite.backend.models.PermissionKey;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.tenant.EntitlementResolverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PermissionSecurityServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private EntitlementResolverService entitlementResolverService;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private PermissionSecurityService permissionSecurityService;

    private Tenant tenant;
    private User owner;
    private User agent;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = new Tenant();
        tenant.setId(tenantId);

        owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setEmail("owner@example.com");
        owner.setRole(User.Role.OWNER);
        owner.setAccountStatus(User.AccountStatus.ACTIVE);
        owner.setTenant(tenant);

        agent = new User();
        agent.setId(UUID.randomUUID());
        agent.setEmail("agent@example.com");
        agent.setRole(User.Role.AGENT);
        agent.setAccountStatus(User.AccountStatus.ACTIVE);
        agent.setTenant(tenant);
        agent.setPermissions(List.of("MODULE_INBOX", "MODULE_LEADS", "MODULE_SETTINGS", "SETTINGS_PROFILE"));
    }

    @Test
    @DisplayName("Owner should be granted access when entitled")
    void owner_implicit_access() {
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("owner@example.com");
        when(userRepository.findByEmailWithTenant("owner@example.com")).thenReturn(Optional.of(owner));
        when(entitlementResolverService.getEffectiveEntitlements(tenantId))
                .thenReturn(EffectiveEntitlementsDTO.builder().build());

        boolean result = permissionSecurityService.has(authentication, "MODULE_INBOX");
        assertTrue(result);
    }

    @Test
    @DisplayName("Agent with permission should be granted access")
    void agent_granted_permission() {
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("agent@example.com");
        when(userRepository.findByEmailWithTenant("agent@example.com")).thenReturn(Optional.of(agent));
        when(entitlementResolverService.getEffectiveEntitlements(tenantId))
                .thenReturn(EffectiveEntitlementsDTO.builder().build());

        boolean result = permissionSecurityService.has(authentication, "MODULE_LEADS");
        assertTrue(result);
    }

    @Test
    @DisplayName("Agent without permission should be denied access")
    void agent_denied_permission() {
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("agent@example.com");
        when(userRepository.findByEmailWithTenant("agent@example.com")).thenReturn(Optional.of(agent));

        boolean result = permissionSecurityService.has(authentication, "MODULE_CAMPAIGNS");
        assertFalse(result);
    }

    @Test
    @DisplayName("Agent attempting to access admin-only permission should be denied")
    void agent_admin_only_permission_denied() {
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("agent@example.com");
        agent.setPermissions(List.of("MODULE_TEAM")); // Maliciously present
        when(userRepository.findByEmailWithTenant("agent@example.com")).thenReturn(Optional.of(agent));

        boolean result = permissionSecurityService.has(authentication, "MODULE_TEAM");
        assertFalse(result);
    }

    @Test
    @DisplayName("Child permission denied when parent permission is missing")
    void child_permission_denied_without_parent() {
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("agent@example.com");
        // SETTINGS_WHATSAPP present, but MODULE_SETTINGS (parent) is missing!
        agent.setPermissions(List.of("SETTINGS_WHATSAPP"));
        when(userRepository.findByEmailWithTenant("agent@example.com")).thenReturn(Optional.of(agent));

        boolean result = permissionSecurityService.has(authentication, "SETTINGS_WHATSAPP");
        assertFalse(result);
    }

    @Test
    @DisplayName("Suspended user should be denied access regardless of role or permissions")
    void suspended_user_denied() {
        agent.setAccountStatus(User.AccountStatus.SUSPENDED);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("agent@example.com");
        when(userRepository.findByEmailWithTenant("agent@example.com")).thenReturn(Optional.of(agent));

        boolean result = permissionSecurityService.has(authentication, "MODULE_INBOX");
        assertFalse(result);
    }

    @Test
    @DisplayName("Campaign access denied if tenant subscription is not entitled even for Owner")
    void campaign_access_denied_if_subscription_not_entitled() {
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("owner@example.com");
        when(userRepository.findByEmailWithTenant("owner@example.com")).thenReturn(Optional.of(owner));
        
        EffectiveEntitlementsDTO entitlements = EffectiveEntitlementsDTO.builder()
                .features(EffectiveEntitlementsDTO.FeaturesDTO.builder().hasWhatsappCampaign(false).build())
                .build();
        when(entitlementResolverService.getEffectiveEntitlements(tenantId)).thenReturn(entitlements);

        boolean result = permissionSecurityService.has(authentication, "MODULE_CAMPAIGNS");
        assertFalse(result);
    }
}
