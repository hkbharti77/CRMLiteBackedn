package com.chatcrmlite.backend.dto.payments;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ProviderPaymentCreationResult {
    boolean success;
    String providerOrderId;
    String checkoutUrl;
    String rawReference;
    String errorCode;
    String errorMessage;
}
