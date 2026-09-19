package com.chatcrmlite.backend.dto.payments;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PaymentCapabilities {
    boolean supportsNativeWhatsApp;
    boolean supportsPaymentLink;
    boolean supportsRefund;
    boolean supportsPartialRefund;
    boolean supportsRecurring;
}
