package com.chatcrmlite.backend.controllers.admin;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.tenant.TenantResourceManager;
import com.chatcrmlite.backend.services.tenant.TenantTierService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminTenantControllerTest {

    @Mock
    private TenantResourceManager resourceManager;

    @Mock
    private TenantTierService tierService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AdminTenantController controller;

    private Tenant tenantA;
    private Tenant tenantB;
    private User tenantAdminA;
    private User superAdminUser;
    private UUID tenantAId;
    private UUID tenantBId;

    @BeforeEach
    void setUp() {
        tenantAId = UUID.randomUUID();
        tenantA = new Tenant();
        tenantA.setId(tenantAId);

        tenantBId = UUID.randomUUID();
        tenantB = new Tenant();
        tenantB.setId(tenantBId);

        tenantAdminA = User.builder()
                .id(UUID.randomUUID())
                .email("admin@tenanta.com")
                .role(User.Role.ADMIN)
                .tenant(tenantA)
                .build();

        superAdminUser = User.builder()
                .id(UUID.randomUUID())
                .email("superadmin@platform.com")
                .role(User.Role.SUPER_ADMIN)
                .tenant(null)
                .build();
    }

    @Test
    @DisplayName("TEST 10: Tenant A ADMIN requests Tenant A stats → ALLOW (200)")
    void testTenantAdmin_OwnTenantStats_Allowed() {
        when(userRepository.findByEmail(tenantAdminA.getEmail())).thenReturn(Optional.of(tenantAdminA));
        when(tierService.getTier(tenantAId)).thenReturn(User.PlanType.ENTERPRISE);
        when(resourceManager.getStatus(eq(tenantAId), any())).thenReturn(new TenantResourceManager.QuotaStatus(10, 100));

        ResponseEntity<Map<String, Object>> response = controller.getTenantStats(tenantAId, tenantAdminA.getEmail());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(tenantAId, response.getBody().get("tenantId"));
        assertEquals(User.PlanType.ENTERPRISE, response.getBody().get("tier"));
    }

    @Test
    @DisplayName("TEST 11: Tenant A ADMIN requests Tenant B stats → FORBIDDEN (403)")
    void testTenantAdmin_ForeignTenantStats_Forbidden() {
        when(userRepository.findByEmail(tenantAdminA.getEmail())).thenReturn(Optional.of(tenantAdminA));

        ResponseEntity<Map<String, Object>> response = controller.getTenantStats(tenantBId, tenantAdminA.getEmail());

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNull(response.getBody());
        verify(tierService, never()).getTier(any());
        verify(resourceManager, never()).getStatus(any(), any());
    }

    @Test
    @DisplayName("TEST 12: Platform SUPER_ADMIN requests Tenant B stats → ALLOW (200)")
    void testPlatformSuperAdmin_CrossTenantStats_Allowed() {
        when(userRepository.findByEmail(superAdminUser.getEmail())).thenReturn(Optional.of(superAdminUser));
        when(tierService.getTier(tenantBId)).thenReturn(User.PlanType.PRO);
        when(resourceManager.getStatus(eq(tenantBId), any())).thenReturn(new TenantResourceManager.QuotaStatus(5, 50));

        ResponseEntity<Map<String, Object>> response = controller.getTenantStats(tenantBId, superAdminUser.getEmail());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(tenantBId, response.getBody().get("tenantId"));
        assertEquals(User.PlanType.PRO, response.getBody().get("tier"));
    }
}
