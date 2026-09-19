package com.chatcrmlite.backend.dto.payments;

import com.chatcrmlite.backend.models.enums.WhatsAppSessionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WhatsAppSessionInfoDto {
    private WhatsAppSessionStatus sessionStatus;
    private Instant lastInboundMessageAt;
    private Instant expiresAt;
    private long remainingSeconds;
    private boolean nativePaymentAllowed;
    private boolean directGatewayAllowed;
    private boolean templateRequired;
    private List<AvailableTemplateDto> availableTemplates;
}
