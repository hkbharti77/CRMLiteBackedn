package com.chatcrmlite.backend.controllers.crm;

import com.chatcrmlite.backend.dto.crm.ContactMergeHistoryDTO;
import com.chatcrmlite.backend.dto.crm.DuplicateCandidateDTO;
import com.chatcrmlite.backend.dto.crm.MergeContactRequestDTO;
import com.chatcrmlite.backend.dto.crm.MergeResultDTO;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.crm.ContactMergeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContactMergeControllerTest {

    @Mock
    private ContactMergeService mergeService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private ContactMergeController controller;

    private User currentUser;
    private UUID primaryId;
    private UUID secondaryId;

    @BeforeEach
    void setUp() {
        primaryId = UUID.randomUUID();
        secondaryId = UUID.randomUUID();

        Tenant tenant = Tenant.builder().id(UUID.randomUUID()).businessName("Test Corp").build();
        currentUser = User.builder()
                .id(UUID.randomUUID())
                .email("admin@testcorp.com")
                .role(User.Role.ADMIN)
                .tenant(tenant)
                .build();

        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(currentUser.getEmail());
        when(userRepository.findByEmail(currentUser.getEmail())).thenReturn(Optional.of(currentUser));
    }

    @Test
    @DisplayName("GET /{id}/duplicate-candidates returns candidates list")
    void testGetDuplicateCandidates() {
        DuplicateCandidateDTO candidate = DuplicateCandidateDTO.builder()
                .contactId(secondaryId)
                .name("John Doe")
                .phone("+919988776655")
                .email("john@example.com")
                .confidenceScore(85)
                .matchReason("PHONE_EXACT")
                .build();

        when(mergeService.findDuplicateCandidates(eq(primaryId), eq(currentUser)))
                .thenReturn(List.of(candidate));

        ResponseEntity<List<DuplicateCandidateDTO>> response = controller.getDuplicateCandidates(primaryId);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals(secondaryId, response.getBody().get(0).getContactId());
    }

    @Test
    @DisplayName("POST /{id}/merge returns successful MergeResultDTO")
    void testMergeContact() {
        MergeContactRequestDTO request = MergeContactRequestDTO.builder()
                .secondaryContactId(secondaryId)
                .fieldResolutions(Map.of("name", "PRIMARY"))
                .auditNote("Verified phone match")
                .build();

        MergeResultDTO mockResult = MergeResultDTO.builder()
                .success(true)
                .primaryContactId(primaryId)
                .secondaryContactId(secondaryId)
                .transferredCounts(Map.of("messages", 8, "leads", 1))
                .mergedAt(Instant.now())
                .message("Contact merged successfully")
                .build();

        when(mergeService.executeContactMerge(eq(primaryId), any(), eq(currentUser)))
                .thenReturn(mockResult);

        ResponseEntity<MergeResultDTO> response = controller.mergeContact(primaryId, request);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().isSuccess());
        assertEquals(8, response.getBody().getTransferredCounts().get("messages"));
    }

    @Test
    @DisplayName("GET /{id}/merge-history returns audit log entries")
    void testGetMergeHistory() {
        ContactMergeHistoryDTO historyItem = ContactMergeHistoryDTO.builder()
                .id(UUID.randomUUID())
                .primaryContactId(primaryId)
                .secondaryContactId(secondaryId)
                .mergedByUserEmail(currentUser.getEmail())
                .auditNote("Test merge")
                .mergedAt(Instant.now())
                .build();

        when(mergeService.getMergeHistory(eq(primaryId), eq(currentUser)))
                .thenReturn(List.of(historyItem));

        ResponseEntity<List<ContactMergeHistoryDTO>> response = controller.getMergeHistory(primaryId);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals(primaryId, response.getBody().get(0).getPrimaryContactId());
    }
}
