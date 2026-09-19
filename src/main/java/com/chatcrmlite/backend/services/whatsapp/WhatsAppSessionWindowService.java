package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.dto.payments.AvailableTemplateDto;
import com.chatcrmlite.backend.dto.payments.WhatsAppSessionInfoDto;
import com.chatcrmlite.backend.models.enums.WhatsAppSessionStatus;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.services.payments.PaymentTemplateDefinitionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class WhatsAppSessionWindowService {

    private final MessageRepository messageRepository;
    private final PaymentTemplateDefinitionRegistry templateRegistry;

    /**
     * Determines whether the 24-hour customer service session window is OPEN, CLOSED, or UNKNOWN.
     */
    @Transactional(readOnly = true)
    public WhatsAppSessionInfoDto getSessionStatus(UUID tenantId, String customerWaId) {
        if (tenantId == null || customerWaId == null || customerWaId.trim().isEmpty()) {
            return buildClosedSessionResponse(null, null, 0);
        }

        String cleanWaId = customerWaId.replaceAll("[^0-9]", "");
        Instant now = Instant.now();

        try {
            Optional<LocalDateTime> lastInboundLdt = messageRepository.findLatestInboundTimestamp(tenantId, cleanWaId);

            if (lastInboundLdt.isEmpty()) {
                // No inbound customer message exists -> CLOSED
                return buildClosedSessionResponse(null, null, 0);
            }

            Instant lastInbound = lastInboundLdt.get().toInstant(ZoneOffset.UTC);
            Instant expiresAt = lastInbound.plus(24, ChronoUnit.HOURS);
            long remainingSeconds = Duration.between(now, expiresAt).getSeconds();

            if (remainingSeconds > 0) {
                // Session is active
                return WhatsAppSessionInfoDto.builder()
                    .sessionStatus(WhatsAppSessionStatus.OPEN)
                    .lastInboundMessageAt(lastInbound)
                    .expiresAt(expiresAt)
                    .remainingSeconds(remainingSeconds)
                    .nativePaymentAllowed(true)
                    .directGatewayAllowed(true)
                    .templateRequired(false)
                    .availableTemplates(templateRegistry.getAllDefinitions())
                    .build();
            } else {
                // Session expired -> CLOSED
                return buildClosedSessionResponse(lastInbound, expiresAt, 0);
            }

        } catch (Exception ex) {
            log.error("Failed to query inbound message timestamp for tenant={} waId={}: {}", tenantId, cleanWaId, ex.getMessage());
            // Fail-safe policy: return UNKNOWN and require templates
            return WhatsAppSessionInfoDto.builder()
                .sessionStatus(WhatsAppSessionStatus.UNKNOWN)
                .lastInboundMessageAt(null)
                .expiresAt(null)
                .remainingSeconds(0)
                .nativePaymentAllowed(false)
                .directGatewayAllowed(false)
                .templateRequired(true)
                .availableTemplates(templateRegistry.getAllDefinitions())
                .build();
        }
    }

    private WhatsAppSessionInfoDto buildClosedSessionResponse(Instant lastInbound, Instant expiresAt, long remaining) {
        return WhatsAppSessionInfoDto.builder()
            .sessionStatus(WhatsAppSessionStatus.CLOSED)
            .lastInboundMessageAt(lastInbound)
            .expiresAt(expiresAt)
            .remainingSeconds(remaining)
            .nativePaymentAllowed(false)
            .directGatewayAllowed(false)
            .templateRequired(true)
            .availableTemplates(templateRegistry.getAllDefinitions())
            .build();
    }
}
