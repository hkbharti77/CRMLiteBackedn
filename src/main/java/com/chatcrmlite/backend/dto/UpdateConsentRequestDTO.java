package com.chatcrmlite.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateConsentRequestDTO {

    /**
     * Target channel: WHATSAPP, EMAIL, SMS, or ALL
     */
    @NotBlank(message = "Channel is required")
    private String channel;

    /**
     * Target status: OPTED_IN, OPTED_OUT, or UNKNOWN
     */
    @NotBlank(message = "Consent status is required")
    private String status;

    /**
     * Optional reason for change (e.g. "Customer requested via call", "Explicit opt-out link click")
     */
    private String reason;

    /**
     * Source of update: ADMIN_MANUAL, WEB_WIDGET, PUBLIC_FORM, API, WHATSAPP_KEYWORD
     */
    private String source;

    /**
     * Optional global suppression flag toggle
     */
    private Boolean isGloballySuppressed;
}
