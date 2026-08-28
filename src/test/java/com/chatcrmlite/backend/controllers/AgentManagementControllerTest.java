package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.team.AgentAnalyticsService;
import com.chatcrmlite.backend.services.team.AgentAssignmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentManagementControllerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private LeadRepository leadRepository;

    @Mock
    private AgentAssignmentService agentAssignmentService;

    @Mock
    private AgentAnalyticsService agentAnalyticsService;

    @Mock
    private Authentication authentication;

    @Mock
    private SecurityContext securityContext;

    @InjectMocks
    private AgentManagementController controller;

    private Tenant tenantA;
    private Tenant tenantB;
    private User requesterA;
    private User agentA;
    private User agentB;
    private Lead leadA;
    private Lead leadB;
    private UUID leadAId;
    private UUID leadBId;
    private UUID agentAId;
    private UUID agentBId;

    @BeforeEach
    void setUp() {
        tenantA = new Tenant();
        tenantA.setId(UUID.randomUUID());
        tenantA.setBusinessName("Tenant A");

        tenantB = new Tenant();
        tenantB.setId(UUID.randomUUID());
        tenantB.setBusinessName("Tenant B");

        requesterA = User.builder()
                .id(UUID.randomUUID())
                .email("requester@tenanta.com")
                .role(User.Role.ADMIN)
                .tenant(tenantA)
                .build();

        agentAId = UUID.randomUUID();
        agentA = User.builder()
                .id(agentAId)
                .email("agentA@tenanta.com")
                .role(User.Role.AGENT)
                .tenant(tenantA)
                .displayName("Agent A")
                .build();

        agentBId = UUID.randomUUID();
        agentB = User.builder()
                .id(agentBId)
                .email("agentB@tenantb.com")
                .role(User.Role.AGENT)
                .tenant(tenantB)
                .displayName("Agent B")
                .build();

        leadAId = UUID.randomUUID();
        leadA = new Lead();
        leadA.setId(leadAId);
        leadA.setOwner(requesterA);

        leadBId = UUID.randomUUID();
        leadB = new Lead();
        leadB.setId(leadBId);
        User ownerB = User.builder().id(UUID.randomUUID()).tenant(tenantB).build();
        leadB.setOwner(ownerB);

        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);
        when(authentication.getPrincipal()).thenReturn(requesterA.getEmail());
    }

    @Test
    @DisplayName("TEST 13: Tenant A lead -> Tenant A agent is ALLOWED (200)")
    void testAssignLead_SameTenant_Allowed() {
        when(userRepository.findByEmail(requesterA.getEmail())).thenReturn(Optional.of(requesterA));
        when(leadRepository.findById(leadAId)).thenReturn(Optional.of(leadA));
        when(userRepository.findById(agentAId)).thenReturn(Optional.of(agentA));
        when(leadRepository.save(any(Lead.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<Map<String, Object>> response = controller.assignLeadToAgent(
                leadAId,
                Map.of("agentId", agentAId.toString())
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(agentA, leadA.getOwner());
        verify(leadRepository).save(leadA);
    }

    @Test
    @DisplayName("TEST 14: Tenant A lead -> Tenant B agent is FORBIDDEN (403) and lead owner is unchanged")
    void testAssignLead_ForeignAgent_Forbidden() {
        when(userRepository.findByEmail(requesterA.getEmail())).thenReturn(Optional.of(requesterA));
        when(leadRepository.findById(leadAId)).thenReturn(Optional.of(leadA));
        when(userRepository.findById(agentBId)).thenReturn(Optional.of(agentB));

        ResponseEntity<Map<String, Object>> response = controller.assignLeadToAgent(
                leadAId,
                Map.of("agentId", agentBId.toString())
        );

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(requesterA, leadA.getOwner(), "Lead owner must remain unchanged");
        verify(leadRepository, never()).save(any(Lead.class));
    }

    @Test
    @DisplayName("TEST 15: Tenant B lead -> Tenant A agent is FORBIDDEN (403) and lead owner is unchanged")
    void testAssignForeignLead_Forbidden() {
        when(userRepository.findByEmail(requesterA.getEmail())).thenReturn(Optional.of(requesterA));
        when(leadRepository.findById(leadBId)).thenReturn(Optional.of(leadB));

        ResponseEntity<Map<String, Object>> response = controller.assignLeadToAgent(
                leadBId,
                Map.of("agentId", agentAId.toString())
        );

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(userRepository, never()).findById(agentAId);
        verify(leadRepository, never()).save(any(Lead.class));
    }

    // -------------------------------------------------------------
    // P2-03-03: Security tests for updateAvailability
    // -------------------------------------------------------------

    @Test
    @DisplayName("Security 1: Agent can update their own availability")
    void testAgentCanUpdateOwnAvailability() {
        agentA.setAvailabilityStatus(User.AvailabilityStatus.AVAILABLE);
        when(authentication.getPrincipal()).thenReturn(agentA.getEmail());
        when(userRepository.findByEmail(agentA.getEmail())).thenReturn(Optional.of(agentA));
        when(userRepository.findById(agentAId)).thenReturn(Optional.of(agentA));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<Map<String, Object>> response = controller.updateAvailability(
                agentAId,
                Map.of("availabilityStatus", "BUSY")
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(User.AvailabilityStatus.BUSY, agentA.getAvailabilityStatus());
        verify(userRepository).save(agentA);
    }

    @Test
    @DisplayName("Security 2: Agent cannot update another agent's availability")
    void testAgentCannotUpdateAnotherAgent() {
        UUID agentA2Id = UUID.randomUUID();
        User agentA2 = User.builder()
                .id(agentA2Id)
                .email("agentA2@tenanta.com")
                .role(User.Role.AGENT)
                .tenant(tenantA)
                .build();
        agentA2.setAvailabilityStatus(User.AvailabilityStatus.AVAILABLE);

        when(authentication.getPrincipal()).thenReturn(agentA.getEmail());
        when(userRepository.findByEmail(agentA.getEmail())).thenReturn(Optional.of(agentA));
        when(userRepository.findById(agentA2Id)).thenReturn(Optional.of(agentA2));

        ResponseEntity<Map<String, Object>> response = controller.updateAvailability(
                agentA2Id,
                Map.of("availabilityStatus", "BUSY")
        );

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(User.AvailabilityStatus.AVAILABLE, agentA2.getAvailabilityStatus(), "Target agent status must not change");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Security 3: Agent cannot update an ADMIN/OWNER")
    void testAgentCannotUpdateAdminOrOwner() {
        requesterA.setAvailabilityStatus(User.AvailabilityStatus.AVAILABLE);
        when(authentication.getPrincipal()).thenReturn(agentA.getEmail());
        when(userRepository.findByEmail(agentA.getEmail())).thenReturn(Optional.of(agentA));
        when(userRepository.findById(requesterA.getId())).thenReturn(Optional.of(requesterA));

        ResponseEntity<Map<String, Object>> response = controller.updateAvailability(
                requesterA.getId(),
                Map.of("availabilityStatus", "OFFLINE")
        );

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(User.AvailabilityStatus.AVAILABLE, requesterA.getAvailabilityStatus(), "Admin status must not change");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Security 4: ADMIN can update an agent in the same tenant")
    void testAdminCanUpdateAgentInSameTenant() {
        agentA.setAvailabilityStatus(User.AvailabilityStatus.AVAILABLE);
        when(userRepository.findByEmail(requesterA.getEmail())).thenReturn(Optional.of(requesterA));
        when(userRepository.findById(agentAId)).thenReturn(Optional.of(agentA));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<Map<String, Object>> response = controller.updateAvailability(
                agentAId,
                Map.of("availabilityStatus", "OFFLINE")
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(User.AvailabilityStatus.OFFLINE, agentA.getAvailabilityStatus());
        verify(userRepository).save(agentA);
    }

    @Test
    @DisplayName("Security 5: OWNER can update an agent in the same tenant")
    void testOwnerCanUpdateAgentInSameTenant() {
        User ownerA = User.builder()
                .id(UUID.randomUUID())
                .email("owner@tenanta.com")
                .role(User.Role.OWNER)
                .tenant(tenantA)
                .build();

        agentA.setAvailabilityStatus(User.AvailabilityStatus.AVAILABLE);
        when(authentication.getPrincipal()).thenReturn(ownerA.getEmail());
        when(userRepository.findByEmail(ownerA.getEmail())).thenReturn(Optional.of(ownerA));
        when(userRepository.findById(agentAId)).thenReturn(Optional.of(agentA));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<Map<String, Object>> response = controller.updateAvailability(
                agentAId,
                Map.of("availabilityStatus", "BUSY")
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(User.AvailabilityStatus.BUSY, agentA.getAvailabilityStatus());
        verify(userRepository).save(agentA);
    }

    @Test
    @DisplayName("Security 6: ADMIN cannot update a user in another tenant")
    void testAdminCannotUpdateUserInAnotherTenant() {
        agentB.setAvailabilityStatus(User.AvailabilityStatus.AVAILABLE);
        when(userRepository.findByEmail(requesterA.getEmail())).thenReturn(Optional.of(requesterA));
        when(userRepository.findById(agentBId)).thenReturn(Optional.of(agentB));

        ResponseEntity<Map<String, Object>> response = controller.updateAvailability(
                agentBId,
                Map.of("availabilityStatus", "BUSY")
        );

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(User.AvailabilityStatus.AVAILABLE, agentB.getAvailabilityStatus(), "Cross-tenant user status must not change");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Security 7: OWNER cannot update a user in another tenant")
    void testOwnerCannotUpdateUserInAnotherTenant() {
        User ownerA = User.builder()
                .id(UUID.randomUUID())
                .email("owner@tenanta.com")
                .role(User.Role.OWNER)
                .tenant(tenantA)
                .build();

        agentB.setAvailabilityStatus(User.AvailabilityStatus.AVAILABLE);
        when(authentication.getPrincipal()).thenReturn(ownerA.getEmail());
        when(userRepository.findByEmail(ownerA.getEmail())).thenReturn(Optional.of(ownerA));
        when(userRepository.findById(agentBId)).thenReturn(Optional.of(agentB));

        ResponseEntity<Map<String, Object>> response = controller.updateAvailability(
                agentBId,
                Map.of("availabilityStatus", "BUSY")
        );

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(User.AvailabilityStatus.AVAILABLE, agentB.getAvailabilityStatus(), "Cross-tenant user status must not change");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Security 8: Null/missing tenant fails closed")
    void testNullMissingTenantFailsClosed() {
        // Subcase 8a: Requester has null tenant
        User requesterNoTenant = User.builder()
                .id(UUID.randomUUID())
                .email("notenant@tenanta.com")
                .role(User.Role.ADMIN)
                .tenant(null)
                .build();

        when(authentication.getPrincipal()).thenReturn(requesterNoTenant.getEmail());
        when(userRepository.findByEmail(requesterNoTenant.getEmail())).thenReturn(Optional.of(requesterNoTenant));
        when(userRepository.findById(agentAId)).thenReturn(Optional.of(agentA));

        ResponseEntity<Map<String, Object>> response1 = controller.updateAvailability(
                agentAId,
                Map.of("availabilityStatus", "BUSY")
        );
        assertEquals(HttpStatus.FORBIDDEN, response1.getStatusCode());

        // Subcase 8b: Target has null tenant
        User targetNoTenant = User.builder()
                .id(UUID.randomUUID())
                .email("targetnotenant@tenanta.com")
                .role(User.Role.AGENT)
                .tenant(null)
                .build();
        targetNoTenant.setAvailabilityStatus(User.AvailabilityStatus.AVAILABLE);

        when(authentication.getPrincipal()).thenReturn(requesterA.getEmail());
        when(userRepository.findByEmail(requesterA.getEmail())).thenReturn(Optional.of(requesterA));
        when(userRepository.findById(targetNoTenant.getId())).thenReturn(Optional.of(targetNoTenant));

        ResponseEntity<Map<String, Object>> response2 = controller.updateAvailability(
                targetNoTenant.getId(),
                Map.of("availabilityStatus", "BUSY")
        );
        assertEquals(HttpStatus.FORBIDDEN, response2.getStatusCode());
        assertEquals(User.AvailabilityStatus.AVAILABLE, targetNoTenant.getAvailabilityStatus());

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Security 9: Nonexistent target user is handled safely")
    void testNonexistentTargetUserHandledSafely() {
        UUID nonexistentId = UUID.randomUUID();
        when(userRepository.findByEmail(requesterA.getEmail())).thenReturn(Optional.of(requesterA));
        when(userRepository.findById(nonexistentId)).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> controller.updateAvailability(
                nonexistentId,
                Map.of("availabilityStatus", "BUSY")
        ));

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Security 10: Verify rejected requests do not modify the target user's availability")
    void testRejectedRequestsDoNotModifyTargetAvailability() {
        agentB.setAvailabilityStatus(User.AvailabilityStatus.AVAILABLE);

        when(authentication.getPrincipal()).thenReturn(agentA.getEmail());
        when(userRepository.findByEmail(agentA.getEmail())).thenReturn(Optional.of(agentA));
        when(userRepository.findById(agentBId)).thenReturn(Optional.of(agentB));

        ResponseEntity<Map<String, Object>> response = controller.updateAvailability(
                agentBId,
                Map.of("availabilityStatus", "OFFLINE")
        );

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(User.AvailabilityStatus.AVAILABLE, agentB.getAvailabilityStatus(), "Target availability status must remain unchanged");
        verify(userRepository, never()).save(any(User.class));
    }
}
