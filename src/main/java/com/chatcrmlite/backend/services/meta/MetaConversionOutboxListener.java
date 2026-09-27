package com.chatcrmlite.backend.services.meta;

import com.chatcrmlite.backend.event.LeadCreatedEvent;
import com.chatcrmlite.backend.models.MetaConversionEvent;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.MetaConversionEventRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetaConversionOutboxListener {

    private final MetaConversionEventRepository outboxRepository;
    private final WhatsAppConfigRepository whatsappConfigRepository;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY) // Must run within the Lead save transaction
    public void handleLeadCreatedEvent(LeadCreatedEvent event) {
        log.info("[Meta Outbox] Received LeadCreatedEvent for Lead: {}", event.getLead().getId());
        
        UUID tenantId = event.getLead().getOwner().getTenant().getId();
        
        // Find WABA details to populate outbox correctly. (If missing, we'll mark dataset as null)
        WhatsAppConfig config = whatsappConfigRepository.findByTenantId(tenantId)
                .stream().findFirst().orElse(null);
                
        String wabaId = config != null ? config.getWabaId() : null;
        String datasetId = config != null ? config.getDatasetId() : null;

        // Default to organic for now unless attribution is directly linked
        String ctwaClid = null;
        String sourceType = "ORGANIC_WHATSAPP";

        // Deterministic Event ID for idempotency: META-WA-LEADSUBMITTED-{leadUUID}-v1
        String eventId = String.format("META-WA-LEADSUBMITTED-%s-v1", event.getLead().getId());

        MetaConversionEvent outboxEvent = MetaConversionEvent.builder()
                .tenantId(tenantId)
                .leadId(event.getLead().getId())
                .eventId(eventId)
                .eventName("LeadSubmitted")
                .sourceType(sourceType)
                .wabaId(wabaId)
                .datasetId(datasetId)
                .ctwaClid(ctwaClid)
                .payloadVersion("v1")
                .status(MetaConversionEvent.EventStatus.PENDING)
                .attemptCount(0)
                .nextAttemptAt(LocalDateTime.now())
                .build();

        outboxRepository.save(outboxEvent);
        log.info("[Meta Outbox] Outbox event saved with ID: {} and status PENDING", eventId);
    }
}
