package com.chatcrmlite.backend.services.crm;

import com.chatcrmlite.backend.dto.crm.DuplicateCandidateDTO;
import com.chatcrmlite.backend.dto.crm.MergeContactRequestDTO;
import com.chatcrmlite.backend.dto.crm.MergeResultDTO;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tag;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.crm.ContactMergeHistory;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.crm.ContactMergeHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContactMergeServiceTest {

    @Mock
    private ContactRepository contactRepository;

    @Mock
    private ContactMergeHistoryRepository mergeHistoryRepository;

    @Mock
    private EntityManager entityManager;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ContactMergeService mergeService;

    private Tenant tenantA;
    private Tenant tenantB;
    private User userA;
    private Contact primaryContact;
    private Contact secondaryContact;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(mergeService, "entityManager", entityManager);

        tenantA = Tenant.builder().id(UUID.randomUUID()).businessName("Tenant A Corp").build();
        tenantB = Tenant.builder().id(UUID.randomUUID()).businessName("Tenant B Corp").build();

        userA = User.builder()
                .id(UUID.randomUUID())
                .email("agent@tenanta.com")
                .role(User.Role.ADMIN)
                .tenant(tenantA)
                .build();

        Tag tag1 = new Tag(userA, Tag.TYPE_CONTACT, "VIP");
        ReflectionTestUtils.setField(tag1, "id", UUID.randomUUID());

        Tag tag2 = new Tag(userA, Tag.TYPE_CONTACT, "LEAD_INBOUND");
        ReflectionTestUtils.setField(tag2, "id", UUID.randomUUID());

        primaryContact = Contact.builder()
                .id(UUID.randomUUID())
                .name("Alice Sharma")
                .waId("+919876543210")
                .email("alice@example.com")
                .source("WHATSAPP")
                .tags(new ArrayList<>(List.of(tag1)))
                .isMerged(false)
                .build();
        primaryContact.setTenant(tenantA);

        secondaryContact = Contact.builder()
                .id(UUID.randomUUID())
                .name("Alice S.")
                .waId("+919876543210")
                .email("alice.personal@example.com")
                .source("WEBCHAT")
                .tags(new ArrayList<>(List.of(tag2)))
                .isMerged(false)
                .build();
        secondaryContact.setTenant(tenantA);
    }

    @Test
    @DisplayName("Find duplicate candidates by matching phone and email")
    void testFindDuplicateCandidates() {
        when(contactRepository.findByIdWithTags(primaryContact.getId())).thenReturn(Optional.of(primaryContact));
        when(contactRepository.findPotentialDuplicates(eq(tenantA.getId()), eq(primaryContact.getId()), any(), any(), any()))
                .thenReturn(List.of(secondaryContact));

        Query mockCountQuery = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(mockCountQuery);
        when(mockCountQuery.setParameter(anyString(), any())).thenReturn(mockCountQuery);
        when(mockCountQuery.getSingleResult()).thenReturn(5L);

        List<DuplicateCandidateDTO> candidates = mergeService.findDuplicateCandidates(primaryContact.getId(), userA);

        assertNotNull(candidates);
        assertEquals(1, candidates.size());
        DuplicateCandidateDTO dto = candidates.get(0);
        assertEquals(secondaryContact.getId(), dto.getContactId());
        assertTrue(dto.getConfidenceScore() >= 50);
        assertEquals("PHONE_EXACT", dto.getMatchReason());
        assertEquals(5L, dto.getMessageCount());
    }

    @Test
    @DisplayName("Successfully execute merge with entity re-parenting and field resolutions")
    void testExecuteContactMergeSuccess() {
        when(contactRepository.findByIdWithTags(primaryContact.getId())).thenReturn(Optional.of(primaryContact));
        when(contactRepository.findByIdWithTags(secondaryContact.getId())).thenReturn(Optional.of(secondaryContact));

        Query mockUpdateQuery = mock(Query.class);
        when(entityManager.createQuery(startsWith("UPDATE"))).thenReturn(mockUpdateQuery);
        when(mockUpdateQuery.setParameter(anyString(), any())).thenReturn(mockUpdateQuery);
        when(mockUpdateQuery.executeUpdate()).thenReturn(4);

        Query mockConversationStateQuery = mock(Query.class);
        when(entityManager.createQuery(contains("ConversationState"))).thenReturn(mockConversationStateQuery);
        when(mockConversationStateQuery.setParameter(anyString(), any())).thenReturn(mockConversationStateQuery);
        when(mockConversationStateQuery.getSingleResult()).thenReturn(0L);

        MergeContactRequestDTO request = MergeContactRequestDTO.builder()
                .secondaryContactId(secondaryContact.getId())
                .fieldResolutions(Map.of("name", "PRIMARY", "email", "SECONDARY"))
                .tagResolution("UNION")
                .auditNote("Merged duplicate webchat session with primary WhatsApp lead")
                .build();

        MergeResultDTO result = mergeService.executeContactMerge(primaryContact.getId(), request, userA);

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals(primaryContact.getId(), result.getPrimaryContactId());
        assertEquals(secondaryContact.getId(), result.getSecondaryContactId());

        // Assert secondary contact flagged as merged
        assertTrue(secondaryContact.isMerged());
        assertEquals(primaryContact.getId(), secondaryContact.getMergedIntoId());

        // Assert field resolution applied (email from secondary)
        assertEquals("alice.personal@example.com", primaryContact.getEmail());

        // Assert tags unioned
        assertEquals(2, primaryContact.getTags().size());

        // Verify ContactMergeHistory was persisted
        ArgumentCaptor<ContactMergeHistory> historyCaptor = ArgumentCaptor.forClass(ContactMergeHistory.class);
        verify(mergeHistoryRepository).save(historyCaptor.capture());
        ContactMergeHistory savedHistory = historyCaptor.getValue();
        assertEquals(primaryContact.getId(), savedHistory.getPrimaryContact().getId());
        assertEquals(secondaryContact.getId(), savedHistory.getSecondaryContact().getId());
        assertEquals(userA.getEmail(), savedHistory.getMergedByUserEmail());
        assertNotNull(savedHistory.getPreMergeSnapshotJson());
        assertNotNull(savedHistory.getTransferredEntitiesJson());
    }

    @Test
    @DisplayName("Reject merge if primary and secondary IDs are identical (self-merge)")
    void testPreventSelfMerge() {
        MergeContactRequestDTO request = MergeContactRequestDTO.builder()
                .secondaryContactId(primaryContact.getId())
                .build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                mergeService.executeContactMerge(primaryContact.getId(), request, userA));

        assertEquals(400, ex.getStatusCode().value());
        assertTrue(ex.getReason().contains("Cannot merge a contact into itself"));
    }

    @Test
    @DisplayName("Reject merge if secondary contact is already merged")
    void testPreventMergingAlreadyMergedSecondary() {
        secondaryContact.setMerged(true);
        secondaryContact.setMergedIntoId(UUID.randomUUID());

        when(contactRepository.findByIdWithTags(primaryContact.getId())).thenReturn(Optional.of(primaryContact));
        when(contactRepository.findByIdWithTags(secondaryContact.getId())).thenReturn(Optional.of(secondaryContact));

        MergeContactRequestDTO request = MergeContactRequestDTO.builder()
                .secondaryContactId(secondaryContact.getId())
                .build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                mergeService.executeContactMerge(primaryContact.getId(), request, userA));

        assertEquals(409, ex.getStatusCode().value());
        assertTrue(ex.getReason().contains("already been merged"));
    }

    @Test
    @DisplayName("Reject cross-tenant merge attempt")
    void testPreventCrossTenantMerge() {
        secondaryContact.setTenant(tenantB); // Belongs to different tenant

        when(contactRepository.findByIdWithTags(primaryContact.getId())).thenReturn(Optional.of(primaryContact));
        when(contactRepository.findByIdWithTags(secondaryContact.getId())).thenReturn(Optional.of(secondaryContact));

        MergeContactRequestDTO request = MergeContactRequestDTO.builder()
                .secondaryContactId(secondaryContact.getId())
                .build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                mergeService.executeContactMerge(primaryContact.getId(), request, userA));

        assertEquals(403, ex.getStatusCode().value());
    }
}
