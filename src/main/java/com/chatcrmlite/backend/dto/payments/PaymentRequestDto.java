package com.chatcrmlite.backend.dto.payments;

import com.chatcrmlite.backend.models.enums.PaymentDispatchMode;
import com.chatcrmlite.backend.models.enums.PaymentIntegrationType;
import com.chatcrmlite.backend.models.enums.PaymentMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRequestDto {

    private UUID customerId;

    @NotBlank(message = "Recipient WhatsApp number is required")
    private String customerWaId;

    private String customerName;

    private String externalReferenceId;

    @Builder.Default
    private String currency = "INR";

    @Builder.Default
    private Long discountMinor = 0L;

    @Builder.Default
    private Long taxMinor = 0L;

    @Builder.Default
    private Long shippingMinor = 0L;

    private PaymentMode preferredPaymentMode;

    private PaymentDispatchMode dispatchMode;

    private String templateDefinitionKey;

    private Map<String, String> templateParameters;

    private PaymentIntegrationType preferredPaymentProvider;

    @NotEmpty(message = "At least one item is required")
    @Valid
    private List<PaymentItemDto> items;
}
