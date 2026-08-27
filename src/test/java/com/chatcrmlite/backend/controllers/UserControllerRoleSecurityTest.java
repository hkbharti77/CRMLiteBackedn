package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserControllerRoleSecurityTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserController userController;

    private Tenant tenantA;
    private Tenant tenantB;
    private User ownerA;
    private User staffA;
    private User staffB;
    private UUID staffAId;
    private UUID staffBId;

    @BeforeEach
    void setUp() {
        tenantA = new Tenant();
        tenantA.setId(UUID.randomUUID());
        tenantA.setBusinessName("Tenant A");

        tenantB = new Tenant();
        tenantB.setId(UUID.randomUUID());
        tenantB.setBusinessName("Tenant B");

        ownerA = User.builder()
                .id(UUID.randomUUID())
                .email("owner@tenanta.com")
                .role(User.Role.OWNER)
                .tenant(tenantA)
                .build();

        staffAId = UUID.randomUUID();
        staffA = User.builder()
                .id(staffAId)
                .email("agent@tenanta.com")
                .role(User.Role.AGENT)
                .tenant(tenantA)
                .build();

        staffBId = UUID.randomUUID();
        staffB = User.builder()
                .id(staffBId)
                .email("agent@tenantb.com")
                .role(User.Role.AGENT)
                .tenant(tenantB)
                .build();
    }

    @Test
    @DisplayName("TEST 5: OWNER -> ADMIN is ALLOWED")
    void testOwnerUpdatesStaffToAdmin_Success() {
        when(userRepository.findByEmail(ownerA.getEmail())).thenReturn(Optional.of(ownerA));
        when(userRepository.findById(staffAId)).thenReturn(Optional.of(staffA));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> response = userController.updateStaffRole(ownerA.getEmail(), staffAId, User.Role.ADMIN);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(User.Role.ADMIN, staffA.getRole());
        verify(userRepository).save(staffA);
    }

    @Test
    @DisplayName("TEST 6: OWNER -> AGENT is ALLOWED")
    void testOwnerUpdatesStaffToAgent_Success() {
        staffA.setRole(User.Role.ADMIN);
        when(userRepository.findByEmail(ownerA.getEmail())).thenReturn(Optional.of(ownerA));
        when(userRepository.findById(staffAId)).thenReturn(Optional.of(staffA));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> response = userController.updateStaffRole(ownerA.getEmail(), staffAId, User.Role.AGENT);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(User.Role.AGENT, staffA.getRole());
        verify(userRepository).save(staffA);
    }

    @Test
    @DisplayName("TEST 7: OWNER -> SUPER_ADMIN is FORBIDDEN (403) and role remains unchanged")
    void testOwnerCannotAssignSuperAdmin_Forbidden() {
        when(userRepository.findByEmail(ownerA.getEmail())).thenReturn(Optional.of(ownerA));
        when(userRepository.findById(staffAId)).thenReturn(Optional.of(staffA));

        ResponseEntity<?> response = userController.updateStaffRole(ownerA.getEmail(), staffAId, User.Role.SUPER_ADMIN);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(User.Role.AGENT, staffA.getRole(), "Role in memory must remain unchanged");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("TEST 8: OWNER -> OWNER is FORBIDDEN (403) and role remains unchanged")
    void testOwnerCannotAssignOwner_Forbidden() {
        when(userRepository.findByEmail(ownerA.getEmail())).thenReturn(Optional.of(ownerA));
        when(userRepository.findById(staffAId)).thenReturn(Optional.of(staffA));

        ResponseEntity<?> response = userController.updateStaffRole(ownerA.getEmail(), staffAId, User.Role.OWNER);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(User.Role.AGENT, staffA.getRole(), "Role must remain unchanged");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("TEST 9: Cross-tenant role update is FORBIDDEN (403)")
    void testCrossTenantRoleUpdate_Forbidden() {
        when(userRepository.findByEmail(ownerA.getEmail())).thenReturn(Optional.of(ownerA));
        when(userRepository.findById(staffBId)).thenReturn(Optional.of(staffB));

        ResponseEntity<?> response = userController.updateStaffRole(ownerA.getEmail(), staffBId, User.Role.ADMIN);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(User.Role.AGENT, staffB.getRole(), "Cross-tenant user role must not change");
        verify(userRepository, never()).save(any(User.class));
    }
}
