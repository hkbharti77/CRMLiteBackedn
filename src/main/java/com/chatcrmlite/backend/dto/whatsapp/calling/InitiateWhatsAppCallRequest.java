package com.chatcrmlite.backend.dto.whatsapp.calling;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InitiateWhatsAppCallRequest {

    /**
     * Optional Contact UUID belonging to the authenticated tenant.
     * If provided, the contact's phone/WhatsApp ID will be resolved automatically.
     */
    private UUID contactId;

    /**
     * Target WhatsApp user phone number in international format or digits (e.g. 919876543210 or +919876543210).
     * Required if contactId is not provided.
     */
    @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "Phone number must be 7 to 15 digits, optionally prefixed with '+'")
    private String toWaId;

    /**
     * Optional tenant WhatsApp Business Phone Number ID.
     * If omitted, the tenant's primary configured WhatsApp phone number is used.
     */
    @Size(max = 64, message = "Phone number ID must not exceed 64 characters")
    private String phoneNumberId;

    /**
     * Optional client-generated idempotency key (can also be passed via HTTP header 'Idempotency-Key').
     */
    @Size(max = 128, message = "Idempotency key must not exceed 128 characters")
    private String idempotencyKey;

    /**
     * Optional opaque callback metadata for Meta signaling correlation.
     */
    @Size(max = 512, message = "Callback data must not exceed 512 characters")
    private String bizOpaqueCallbackData;
}
