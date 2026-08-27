package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.DocumentChunkRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.RagIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagControllerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RagIngestionService ingestionService;

    @Mock
    private DocumentChunkRepository repository;

    @InjectMocks
    private RagController ragController;

    private User tenantAUser;
    private User tenantBUser;
    private Tenant tenantA;
    private Tenant tenantB;

    @BeforeEach
    void setUp() {
        tenantA = new Tenant();
        tenantA.setId(UUID.randomUUID());
        tenantA.setBusinessName("Tenant A");

        tenantB = new Tenant();
        tenantB.setId(UUID.randomUUID());
        tenantB.setBusinessName("Tenant B");

        tenantAUser = new User();
        tenantAUser.setId(UUID.randomUUID());
        tenantAUser.setEmail("userA@tenantA.com");
        tenantAUser.setTenant(tenantA);

        tenantBUser = new User();
        tenantBUser.setId(UUID.randomUUID());
        tenantBUser.setEmail("userB@tenantB.com");
        tenantBUser.setTenant(tenantB);
    }

    @Test
    @DisplayName("TEST 10: Tenant A requests Tenant A document task status → SUCCESS")
    void testGetStatus_TenantAOwnsTask_ReturnsStatus() {
        when(userRepository.findByEmail("userA@tenantA.com")).thenReturn(Optional.of(tenantAUser));

        CompletableFuture<Map<String, Object>> future = CompletableFuture.completedFuture(
                Map.of("status", "SUCCESS", "totalChunks", 5)
        );
        when(ingestionService.ingestDocument(any(), any(), eq(tenantAUser.getId()), any(UUID.class)))
                .thenReturn(future);

        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "content".getBytes());
        ResponseEntity<Map<String, Object>> uploadResp = ragController.uploadDocument(file, "userA@tenantA.com");
        assertEquals(HttpStatus.OK, uploadResp.getStatusCode());

        UUID docId = (UUID) uploadResp.getBody().get("documentId");
        assertNotNull(docId);

        ResponseEntity<Map<String, Object>> statusResp = ragController.getStatus(docId, "userA@tenantA.com");
        assertEquals(HttpStatus.OK, statusResp.getStatusCode());
        assertEquals("SUCCESS", statusResp.getBody().get("status"));
        assertEquals(5, statusResp.getBody().get("totalChunks"));
    }

    @Test
    @DisplayName("TEST 11: Tenant B requests Tenant A document task status → DENIED / NOT FOUND (404)")
    void testGetStatus_CrossTenant_ReturnsNotFound() {
        when(userRepository.findByEmail("userA@tenantA.com")).thenReturn(Optional.of(tenantAUser));
        when(userRepository.findByEmail("userB@tenantB.com")).thenReturn(Optional.of(tenantBUser));

        CompletableFuture<Map<String, Object>> future = CompletableFuture.completedFuture(
                Map.of("status", "SUCCESS", "totalChunks", 5)
        );
        when(ingestionService.ingestDocument(any(), any(), eq(tenantAUser.getId()), any(UUID.class)))
                .thenReturn(future);

        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "content".getBytes());
        ResponseEntity<Map<String, Object>> uploadResp = ragController.uploadDocument(file, "userA@tenantA.com");
        UUID docId = (UUID) uploadResp.getBody().get("documentId");

        // Tenant B attempts to access Tenant A's in-memory task
        ResponseEntity<Map<String, Object>> statusResp = ragController.getStatus(docId, "userB@tenantB.com");
        assertEquals(HttpStatus.NOT_FOUND, statusResp.getStatusCode());
    }

    @Test
    @DisplayName("TEST 12: Unknown document/task ID → NOT FOUND (404)")
    void testGetStatus_UnknownDocId_ReturnsNotFound() {
        when(userRepository.findByEmail("userA@tenantA.com")).thenReturn(Optional.of(tenantAUser));

        UUID randomDocId = UUID.randomUUID();
        ResponseEntity<Map<String, Object>> statusResp = ragController.getStatus(randomDocId, "userA@tenantA.com");
        assertEquals(HttpStatus.NOT_FOUND, statusResp.getStatusCode());
    }

    @Test
    @DisplayName("Unauthenticated getStatus request returns 401 Unauthorized")
    void testGetStatus_Unauthenticated_ReturnsUnauthorized() {
        ResponseEntity<Map<String, Object>> statusResp = ragController.getStatus(UUID.randomUUID(), null);
        assertEquals(HttpStatus.UNAUTHORIZED, statusResp.getStatusCode());
    }
}
