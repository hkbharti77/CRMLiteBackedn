package com.chatcrmlite.backend.dto.payments;

import com.chatcrmlite.backend.models.enums.PaymentIntegrationStatus;
import com.chatcrmlite.backend.models.enums.PaymentIntegrationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantPaymentConfigDto {

    private UUID id;
    private PaymentIntegrationType integrationType;
    private PaymentIntegrationStatus status;
    private Boolean isActive;
    
    // Masked for security on GET
    private String keyId;
    private String keySecret;
    private String webhookSecret;
    private String metaPaymentConfigurationName;
    private String webhookUrl;
    private String webhookKey; // Provided only on initial creation/regeneration
    
    private String currency;
    private Instant createdAt;
    private Instant updatedAt;
}
