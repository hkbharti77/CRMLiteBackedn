package com.chatcrmlite.backend.event;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.journey.CustomerJourney;
import com.chatcrmlite.backend.models.journey.JourneyOutboxEvent;
import com.chatcrmlite.backend.repositories.journey.CustomerJourneyRepository;
import com.chatcrmlite.backend.security.TenantContext;
import com.chatcrmlite.backend.services.journey.OutboxPublisherService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.ZonedDateTime;
import java.util.*;

/**
 * Enterprise Listener bridging CRM Pipeline Kanban Stage transitions to the
 * Customer Journey Automation Engine (Gap 10).
 *
 * Subscribes to {@link LeadStatusChangedEvent} after transaction commit, evaluates
 * matching published Customer Journeys for the tenant, and enrolls the lead/contact
 * into automated multi-step sequences.
 */
@Component
public class JourneyPipelineEventListener {

    private static final Logger log = LoggerFactory.getLogger(JourneyPipelineEventListener.class);

    private final CustomerJourneyRepository journeyRepository;
    private final OutboxPublisherService outboxPublisherService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ObjectMapper objectMapper;

    public JourneyPipelineEventListener(CustomerJourneyRepository journeyRepository,
                                        OutboxPublisherService outboxPublisherService,
                                        ApplicationEventPublisher applicationEventPublisher,
                                        ObjectMapper objectMapper) {
        this.journeyRepository = journeyRepository;
        this.outboxPublisherService = outboxPublisherService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLeadStatusChanged(LeadStatusChangedEvent event) {
        if (event == null || event.getLead() == null) {
            return;
        }

        Lead lead = event.getLead();
        Contact contact = lead.getContact();
        if (contact == null || contact.getId() == null) {
            log.debug("[JourneyPipelineListener] Lead {} has no associated contact. Skipping journey trigger.", lead.getId());
            return;
        }

        Lead.LeadStatus newStatus = event.getNewStatus();
        Lead.LeadStatus oldStatus = event.getOldStatus();

        String businessId = resolveBusinessId(lead);
        log.info("[JourneyPipelineListener] Evaluating stage change: lead={} contact={} ({} -> {}) biz={}",
                lead.getId(), contact.getId(), oldStatus, newStatus, businessId);

        // Compute candidate trigger keys
        Set<String> candidateTriggers = new LinkedHashSet<>();
        if (newStatus != null) {
            candidateTriggers.add("LEAD_STAGE_" + newStatus.name());

            // Standard business aliases
            if (newStatus == Lead.LeadStatus.WON || newStatus == Lead.LeadStatus.CLOSED_WON) {
                candidateTriggers.add("DEAL_WON");
                candidateTriggers.add("LEAD_STAGE_WON");
            } else if (newStatus == Lead.LeadStatus.LOST || newStatus == Lead.LeadStatus.CLOSED_LOST) {
                candidateTriggers.add("DEAL_LOST");
                candidateTriggers.add("LEAD_STAGE_LOST");
            }
        }
        candidateTriggers.add("LEAD_STATUS_CHANGED");

        try {
            Map<String, Object> payloadMap = new LinkedHashMap<>();
            payloadMap.put("contact_id", contact.getId().toString());
            payloadMap.put("lead_id", lead.getId() != null ? lead.getId().toString() : null);
            payloadMap.put("lead_number", lead.getLeadNumber());
            payloadMap.put("old_status", oldStatus != null ? oldStatus.name() : null);
            payloadMap.put("new_status", newStatus != null ? newStatus.name() : null);
            payloadMap.put("deal_value", lead.getDealValue());
            payloadMap.put("deal_label", lead.getDealLabel());
            payloadMap.put("currency", lead.getCurrency() != null ? lead.getCurrency() : "INR");
            payloadMap.put("owner_id", lead.getOwner() != null ? String.valueOf(lead.getOwner().getId()) : null);
            payloadMap.put("owner_name", lead.getOwner() != null ? lead.getOwner().getName() : null);
            payloadMap.put("owner_email", lead.getOwner() != null ? lead.getOwner().getEmail() : null);
            payloadMap.put("contact_name", contact.getName());
            payloadMap.put("contact_phone", contact.getWaId());
            payloadMap.put("contact_email", contact.getEmail());
            payloadMap.put("triggered_at", ZonedDateTime.now().toString());

            String payloadJson = objectMapper.writeValueAsString(payloadMap);

            for (String triggerEvent : candidateTriggers) {
                List<CustomerJourney> matching = journeyRepository.findByBusinessIdAndTriggerEventAndStatus(
                        businessId, triggerEvent, "PUBLISHED");

                if (matching != null && !matching.isEmpty()) {
                    log.info("[JourneyPipelineListener] Enrolling lead {} into {} published journey(s) for trigger '{}' (biz={})",
                            lead.getId(), matching.size(), triggerEvent, businessId);

                    UUID eventId = UUID.randomUUID();
                    JourneyOutboxEvent outboxEvent = outboxPublisherService.publishEvent(
                            eventId,
                            businessId,
                            "LEAD",
                            lead.getId() != null ? lead.getId().toString() : contact.getId().toString(),
                            triggerEvent,
                            payloadJson
                    );

                    // Dispatch event immediately into Spring application context for real-time journey execution
                    applicationEventPublisher.publishEvent(outboxEvent);
                }
            }
        } catch (Exception e) {
            log.error("[JourneyPipelineListener] Failed to process journey triggers for lead {}: {}",
                    lead.getId(), e.getMessage(), e);
        }
    }

    private String resolveBusinessId(Lead lead) {
        if (lead.getOwner() != null && lead.getOwner().getTenant() != null && lead.getOwner().getTenant().getId() != null) {
            return lead.getOwner().getTenant().getId().toString();
        }
        UUID ctxTenant = TenantContext.getTenantId();
        if (ctxTenant != null) {
            return ctxTenant.toString();
        }
        return "default";
    }
}
