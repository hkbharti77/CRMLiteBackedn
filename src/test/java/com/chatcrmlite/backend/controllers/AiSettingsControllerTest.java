package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AiSettingsController — tests GET/PUT persona endpoints,
 * tenant isolation, role-based security, length validation, and audit tracking.
 */
class AiSettingsControllerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TenantRepository tenantRepository;

    @InjectMocks
    private AiSettingsController controller;

    private User ownerUser;
    private User agentUser;
    private Tenant tenant;
    private UUID tenantId;
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        tenantId = UUID.randomUUID();
        ownerId = UUID.randomUUID();

        tenant = Tenant.builder()
                .id(tenantId)
                .businessName("Test Luxury Estates")
                .aiPersonaPrompt("Initial custom persona")
                .build();

        ownerUser = User.builder()
                .id(ownerId)
                .email("owner@test.com")
                .role(User.Role.OWNER)
                .tenant(tenant)
                .build();

        agentUser = User.builder()
                .id(UUID.randomUUID())
                .email("agent@test.com")
                .role(User.Role.AGENT)
                .tenant(tenant)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────
    //  1. GET /api/v1/settings/ai/persona
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET persona returns 401 if unauthenticated")
    void getPersonaUnauthenticated() {
        ResponseEntity<Map<String, Object>> response = controller.getPersona(null);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    @DisplayName("GET persona returns current persona prompt for authenticated user")
    void getPersonaSuccess() {
        when(userRepository.findByEmail("owner@test.com")).thenReturn(Optional.of(ownerUser));

        ResponseEntity<Map<String, Object>> response = controller.getPersona("owner@test.com");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Initial custom persona", response.getBody().get("aiPersonaPrompt"));
    }

    // ──────────────────────────────────────────────────────────────────────
    //  2. PUT /api/v1/settings/ai/persona (Role Security)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT persona returns 403 Forbidden for AGENT role")
    void updatePersonaForbiddenForAgent() {
        when(userRepository.findByEmail("agent@test.com")).thenReturn(Optional.of(agentUser));

        Map<String, String> body = Map.of("aiPersonaPrompt", "New persona");
        ResponseEntity<Map<String, Object>> response = controller.updatePersona("agent@test.com", body);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(tenantRepository, never()).save(any());
    }

    @Test
    @DisplayName("PUT persona succeeds for OWNER role and updates audit fields")
    void updatePersonaSuccessForOwner() {
        when(userRepository.findByEmail("owner@test.com")).thenReturn(Optional.of(ownerUser));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(i -> i.getArgument(0));

        Map<String, String> body = Map.of("aiPersonaPrompt", "Updated Luxury Concierge Persona");
        ResponseEntity<Map<String, Object>> response = controller.updatePersona("owner@test.com", body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Updated Luxury Concierge Persona", tenant.getAiPersonaPrompt());
        assertEquals(ownerId, tenant.getAiPersonaUpdatedBy());
        assertNotNull(tenant.getAiPersonaUpdatedAt());
        verify(tenantRepository, times(1)).save(tenant);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  3. Validation: Length > 4000 characters
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT persona returns 400 BAD_REQUEST if prompt exceeds 4000 characters")
    void updatePersonaExceedsMaxLength() {
        when(userRepository.findByEmail("owner@test.com")).thenReturn(Optional.of(ownerUser));

        String longPrompt = "A".repeat(4001);
        Map<String, String> body = Map.of("aiPersonaPrompt", longPrompt);

        ResponseEntity<Map<String, Object>> response = controller.updatePersona("owner@test.com", body);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().get("error").toString().contains("4000"));
        verify(tenantRepository, never()).save(any());
    }

    // ──────────────────────────────────────────────────────────────────────
    //  4. Blank persona resets to null (fallback to default)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT persona with blank text resets persona to null")
    void updatePersonaBlankResetsToNull() {
        when(userRepository.findByEmail("owner@test.com")).thenReturn(Optional.of(ownerUser));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(i -> i.getArgument(0));

        Map<String, String> body = Map.of("aiPersonaPrompt", "   ");
        ResponseEntity<Map<String, Object>> response = controller.updatePersona("owner@test.com", body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNull(tenant.getAiPersonaPrompt(), "Blank prompt should be saved as null");
    }
}
