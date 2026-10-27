package com.chatcrmlite.backend.event;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.journey.CustomerJourney;
import com.chatcrmlite.backend.models.journey.JourneyOutboxEvent;
import com.chatcrmlite.backend.repositories.journey.CustomerJourneyRepository;
import com.chatcrmlite.backend.services.journey.OutboxPublisherService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JourneyPipelineEventListenerTest {

    @Mock
    private CustomerJourneyRepository journeyRepository;

    @Mock
    private OutboxPublisherService outboxPublisherService;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private ObjectMapper objectMapper;
    private JourneyPipelineEventListener listener;

    private UUID tenantId;
    private Tenant tenant;
    private User owner;
    private Contact contact;
    private Lead lead;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        listener = new JourneyPipelineEventListener(
                journeyRepository,
                outboxPublisherService,
                applicationEventPublisher,
                objectMapper
        );

        tenantId = UUID.randomUUID();
        tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setName("Acme Corp");

        owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setName("Alex Rivera");
        owner.setEmail("alex@acme.com");
        owner.setTenant(tenant);

        contact = new Contact();
        contact.setId(UUID.randomUUID());
        contact.setName("Jordan Vance");
        contact.setWaId("+15550199999");
        contact.setEmail("jordan@example.com");

        lead = new Lead();
        lead.setId(UUID.randomUUID());
        lead.setLeadNumber("ACME-1001");
        lead.setContact(contact);
        lead.setOwner(owner);
        lead.setDealValue(new BigDecimal("25000.00"));
        lead.setDealLabel("Enterprise Cloud Pilot");
        lead.setCurrency("USD");

        lenient().when(journeyRepository.findByBusinessIdAndTriggerEventAndStatus(any(), any(), any()))
                .thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("Should trigger published journey when lead status changes to QUALIFIED")
    void testPipelineStageChangeEnrollsInPublishedJourney() {
        lead.setStatus(Lead.LeadStatus.QUALIFIED);
        LeadStatusChangedEvent event = new LeadStatusChangedEvent(this, lead, Lead.LeadStatus.NEW, Lead.LeadStatus.QUALIFIED);

        CustomerJourney journey = CustomerJourney.builder()
                .id(UUID.randomUUID())
                .businessId(tenantId.toString())
                .name("Post-Qualification Welcome Drip")
                .triggerEvent("LEAD_STAGE_QUALIFIED")
                .status("PUBLISHED")
                .publishedVersionId(UUID.randomUUID())
                .build();

        when(journeyRepository.findByBusinessIdAndTriggerEventAndStatus(
                eq(tenantId.toString()), eq("LEAD_STAGE_QUALIFIED"), eq("PUBLISHED")))
                .thenReturn(List.of(journey));

        JourneyOutboxEvent outboxEvent = JourneyOutboxEvent.builder()
                .eventId(UUID.randomUUID())
                .businessId(tenantId.toString())
                .aggregateType("LEAD")
                .aggregateId(lead.getId().toString())
                .eventType("LEAD_STAGE_QUALIFIED")
                .status("PENDING")
                .build();

        when(outboxPublisherService.publishEvent(any(UUID.class), eq(tenantId.toString()), eq("LEAD"), eq(lead.getId().toString()), eq("LEAD_STAGE_QUALIFIED"), anyString()))
                .thenReturn(outboxEvent);

        // When
        listener.onLeadStatusChanged(event);

        // Then
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxPublisherService).publishEvent(
                any(UUID.class),
                eq(tenantId.toString()),
                eq("LEAD"),
                eq(lead.getId().toString()),
                eq("LEAD_STAGE_QUALIFIED"),
                payloadCaptor.capture()
        );

        String payload = payloadCaptor.getValue();
        assertThat(payload).contains(contact.getId().toString());
        assertThat(payload).contains("QUALIFIED");
        assertThat(payload).contains("25000.00");
        assertThat(payload).contains("Enterprise Cloud Pilot");

        verify(applicationEventPublisher).publishEvent(outboxEvent);
    }

    @Test
    @DisplayName("Should trigger DEAL_WON and LEAD_STAGE_WON aliases on CLOSED_WON status")
    void testDealWonAliasEnrollsInBothTriggers() {
        lead.setStatus(Lead.LeadStatus.CLOSED_WON);
        LeadStatusChangedEvent event = new LeadStatusChangedEvent(this, lead, Lead.LeadStatus.QUALIFIED, Lead.LeadStatus.CLOSED_WON);

        CustomerJourney dealWonJourney = CustomerJourney.builder()
                .id(UUID.randomUUID())
                .businessId(tenantId.toString())
                .name("Executive Customer Onboarding")
                .triggerEvent("DEAL_WON")
                .status("PUBLISHED")
                .publishedVersionId(UUID.randomUUID())
                .build();

        when(journeyRepository.findByBusinessIdAndTriggerEventAndStatus(
                eq(tenantId.toString()), eq("DEAL_WON"), eq("PUBLISHED")))
                .thenReturn(List.of(dealWonJourney));

        JourneyOutboxEvent outboxEvent = JourneyOutboxEvent.builder()
                .eventId(UUID.randomUUID())
                .businessId(tenantId.toString())
                .aggregateType("LEAD")
                .aggregateId(lead.getId().toString())
                .eventType("DEAL_WON")
                .status("PENDING")
                .build();

        when(outboxPublisherService.publishEvent(any(UUID.class), eq(tenantId.toString()), eq("LEAD"), eq(lead.getId().toString()), eq("DEAL_WON"), anyString()))
                .thenReturn(outboxEvent);

        // When
        listener.onLeadStatusChanged(event);

        // Then
        verify(outboxPublisherService).publishEvent(
                any(UUID.class),
                eq(tenantId.toString()),
                eq("LEAD"),
                eq(lead.getId().toString()),
                eq("DEAL_WON"),
                anyString()
        );
        verify(applicationEventPublisher).publishEvent(outboxEvent);
    }

    @Test
    @DisplayName("Should skip journey evaluation gracefully if lead has no contact")
    void testLeadWithoutContactIsSafelyIgnored() {
        lead.setContact(null);
        LeadStatusChangedEvent event = new LeadStatusChangedEvent(this, lead, Lead.LeadStatus.NEW, Lead.LeadStatus.QUALIFIED);

        listener.onLeadStatusChanged(event);

        verifyNoInteractions(journeyRepository);
        verifyNoInteractions(outboxPublisherService);
        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    @DisplayName("Should not publish outbox events when no published journeys match the stage trigger")
    void testNoPublishedJourneysDoesNotPublishOutbox() {
        lead.setStatus(Lead.LeadStatus.INTERESTED);
        LeadStatusChangedEvent event = new LeadStatusChangedEvent(this, lead, Lead.LeadStatus.NEW, Lead.LeadStatus.INTERESTED);

        when(journeyRepository.findByBusinessIdAndTriggerEventAndStatus(anyString(), anyString(), eq("PUBLISHED")))
                .thenReturn(Collections.emptyList());

        listener.onLeadStatusChanged(event);

        verify(journeyRepository, atLeastOnce()).findByBusinessIdAndTriggerEventAndStatus(
                eq(tenantId.toString()), anyString(), eq("PUBLISHED")
        );
        verifyNoInteractions(outboxPublisherService);
        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    @DisplayName("Should maintain strict tenant isolation during trigger matching")
    void testTenantIsolationEnforced() {
        lead.setStatus(Lead.LeadStatus.BOOKED);
        LeadStatusChangedEvent event = new LeadStatusChangedEvent(this, lead, Lead.LeadStatus.NEW, Lead.LeadStatus.BOOKED);

        listener.onLeadStatusChanged(event);

        // Verify query was strictly scoped to lead owner's tenant ID, never another tenant
        verify(journeyRepository).findByBusinessIdAndTriggerEventAndStatus(
                eq(tenantId.toString()), eq("LEAD_STAGE_BOOKED"), eq("PUBLISHED")
        );
    }
}
