package com.chatcrmlite.backend.services.lead;

import com.chatcrmlite.backend.dto.BulkLeadRowDTO;
import com.chatcrmlite.backend.dto.RowErrorDTO;
import com.chatcrmlite.backend.event.LeadCreatedEvent;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.services.ReferenceNumberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BulkLeadPersister}.
 *
 * <p>No Spring context is loaded — all collaborators are mocked via Mockito.
 *
 * <p>Validates: Requirements 5.3
 */
@ExtendWith(MockitoExtension.class)
class BulkLeadPersisterTest {

    @Mock
    private ContactRepository contactRepository;

    @Mock
    private LeadRepository leadRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private ReferenceNumberService referenceNumberService;

    @Mock
    private LeadService leadService;

    private BulkLeadPersister persister;

    private User owner;

    @BeforeEach
    void setUp() {
        persister = new BulkLeadPersister(
                contactRepository,
                leadRepository,
                applicationEventPublisher,
                referenceNumberService,
                leadService
        );

        Tenant tenant = Tenant.builder()
                .id(UUID.randomUUID())
                .businessName("Acme Corp")
                .build();

        owner = User.builder()
                .id(UUID.randomUUID())
                .email("owner@acme.com")
                .tenant(tenant)
                .build();
    }

    // ── Helper: build a saved Contact stub ───────────────────────────────

    private Contact savedContact(String waId) {
        Contact c = Contact.builder()
                .id(UUID.randomUUID())
                .waId(waId)
                .name("Test Contact")
                .owner(owner)
                .build();
        return c;
    }

    private Lead savedLead(Contact contact) {
        return Lead.builder()
                .id(UUID.randomUUID())
                .contact(contact)
                .owner(owner)
                .status(Lead.LeadStatus.NEW)
                .leadNumber("ACME-L250101-0001")
                .build();
    }

    // ── 1. Single valid row ───────────────────────────────────────────────

    @Test
    @DisplayName("Single valid row creates one Contact and one Lead; returned list has 1 entry")
    void singleValidRowCreatesContactAndLead() {
        BulkLeadRowDTO row = BulkLeadRowDTO.builder()
                .rowNumber(1)
                .name("Alice")
                .email("alice@example.com")
                .phone("9876543210")
                .status("NEW")
                .build();

        Contact stubContact = savedContact("9876543210");
        Lead stubLead = savedLead(stubContact);

        when(referenceNumberService.generate(eq(owner), eq(ReferenceNumberService.EntityType.LEAD)))
                .thenReturn("ACME-L250101-0001");
        when(contactRepository.save(any(Contact.class))).thenReturn(stubContact);
        when(leadRepository.save(any(Lead.class))).thenReturn(stubLead);

        List<RowErrorDTO> errors = new ArrayList<>();
        List<Lead> result = persister.persist(List.of(row), owner, errors);

        // contactRepository.save called exactly once
        verify(contactRepository, times(1)).save(any(Contact.class));
        // leadRepository.save called exactly once
        verify(leadRepository, times(1)).save(any(Lead.class));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(stubLead);
        assertThat(errors).isEmpty();
    }

    // ── 2. One failing row doesn't block others ───────────────────────────

    @Test
    @DisplayName("A row whose contactRepository.save throws does not block subsequent rows")
    void failingRowDoesNotBlockOtherRows() {
        BulkLeadRowDTO failingRow = BulkLeadRowDTO.builder()
                .rowNumber(1)
                .name("Bad Row")
                .email("bad@example.com")
                .phone("1111111111")
                .build();

        BulkLeadRowDTO goodRow = BulkLeadRowDTO.builder()
                .rowNumber(2)
                .name("Good Row")
                .email("good@example.com")
                .phone("2222222222")
                .build();

        Contact goodContact = savedContact("2222222222");
        Lead goodLead = savedLead(goodContact);

        // First call throws, second call succeeds
        when(contactRepository.save(any(Contact.class)))
                .thenThrow(new RuntimeException("DB constraint violation"))
                .thenReturn(goodContact);
        when(leadRepository.save(any(Lead.class))).thenReturn(goodLead);
        when(referenceNumberService.generate(any(), any())).thenReturn("ACME-L250101-0001");

        List<RowErrorDTO> errors = new ArrayList<>();
        List<Lead> result = persister.persist(List.of(failingRow, goodRow), owner, errors);

        // Successful lead still created
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(goodLead);

        // Failing row error appended
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getRowNumber()).isEqualTo(1);
        assertThat(errors.get(0).getReason()).contains("persist failed");
    }

    // ── 3. Blank status defaults to Lead.LeadStatus.NEW ──────────────────

    @Test
    @DisplayName("Row with blank status string results in a Lead saved with status NEW")
    void blankStatusDefaultsToNew() {
        BulkLeadRowDTO row = BulkLeadRowDTO.builder()
                .rowNumber(1)
                .name("Alice")
                .email("alice@example.com")
                .status("")   // blank
                .build();

        Contact stubContact = savedContact("bulk-abc12345");
        when(contactRepository.save(any(Contact.class))).thenReturn(stubContact);
        when(referenceNumberService.generate(any(), any())).thenReturn("ACME-L250101-0001");

        // Capture the Lead that gets passed to leadRepository.save
        ArgumentCaptor<Lead> leadCaptor = ArgumentCaptor.forClass(Lead.class);
        Lead stubLead = savedLead(stubContact);
        when(leadRepository.save(leadCaptor.capture())).thenReturn(stubLead);

        List<RowErrorDTO> errors = new ArrayList<>();
        persister.persist(List.of(row), owner, errors);

        assertThat(errors).isEmpty();
        assertThat(leadCaptor.getValue().getStatus()).isEqualTo(Lead.LeadStatus.NEW);
    }

    // ── 4. LeadCreatedEvent published per successfully created lead ───────

    @Test
    @DisplayName("LeadCreatedEvent is published for every successfully persisted lead")
    void leadCreatedEventPublishedPerLead() {
        BulkLeadRowDTO row1 = BulkLeadRowDTO.builder()
                .rowNumber(1).name("Alice").email("alice@example.com").phone("111").build();
        BulkLeadRowDTO row2 = BulkLeadRowDTO.builder()
                .rowNumber(2).name("Bob").email("bob@example.com").phone("222").build();

        Contact contact1 = savedContact("111");
        Contact contact2 = savedContact("222");
        Lead lead1 = savedLead(contact1);
        Lead lead2 = savedLead(contact2);

        when(contactRepository.save(any(Contact.class)))
                .thenReturn(contact1)
                .thenReturn(contact2);
        when(leadRepository.save(any(Lead.class)))
                .thenReturn(lead1)
                .thenReturn(lead2);
        when(referenceNumberService.generate(any(), any()))
                .thenReturn("ACME-L250101-0001")
                .thenReturn("ACME-L250101-0002");

        ArgumentCaptor<LeadCreatedEvent> eventCaptor =
                ArgumentCaptor.forClass(LeadCreatedEvent.class);

        List<RowErrorDTO> errors = new ArrayList<>();
        List<Lead> result = persister.persist(List.of(row1, row2), owner, errors);

        assertThat(result).hasSize(2);
        assertThat(errors).isEmpty();

        // Event published exactly twice — once per lead
        verify(applicationEventPublisher, times(2)).publishEvent(eventCaptor.capture());

        List<LeadCreatedEvent> capturedEvents = eventCaptor.getAllValues();
        assertThat(capturedEvents).extracting(LeadCreatedEvent::getLead)
                .containsExactly(lead1, lead2);
        assertThat(capturedEvents).extracting(LeadCreatedEvent::getSource)
                .containsOnly("BULK_UPLOAD");
    }

    // ── 5. Blank phone uses "bulk-" prefixed waId ─────────────────────────

    @Test
    @DisplayName("Row with blank phone assigns a 'bulk-' prefixed waId to the Contact")
    void blankPhoneUsesBulkPrefixedWaId() {
        BulkLeadRowDTO row = BulkLeadRowDTO.builder()
                .rowNumber(1)
                .name("Alice")
                .email("alice@example.com")
                .phone("")   // blank phone
                .build();

        // Capture the Contact passed to contactRepository.save
        ArgumentCaptor<Contact> contactCaptor = ArgumentCaptor.forClass(Contact.class);
        Contact stubContact = savedContact("bulk-some1234");
        when(contactRepository.save(contactCaptor.capture())).thenReturn(stubContact);

        Lead stubLead = savedLead(stubContact);
        when(leadRepository.save(any(Lead.class))).thenReturn(stubLead);
        when(referenceNumberService.generate(any(), any())).thenReturn("ACME-L250101-0001");

        List<RowErrorDTO> errors = new ArrayList<>();
        persister.persist(List.of(row), owner, errors);

        assertThat(errors).isEmpty();

        Contact capturedContact = contactCaptor.getValue();
        assertThat(capturedContact.getWaId())
                .as("waId should start with 'bulk-' when phone is blank")
                .startsWith("bulk-");
    }
}
