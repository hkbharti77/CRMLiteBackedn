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
public class PublicOptInRequestDTO {

    private String name;
    private String email;
    
    @NotBlank(message = "Phone/WhatsApp number is required")
    private String phone;

    @Builder.Default
    private boolean whatsappOptIn = true;
    @Builder.Default
    private boolean emailOptIn = true;
    @Builder.Default
    private boolean smsOptIn = true;

    private String source; // e.g. "WEB_WIDGET", "LANDING_PAGE_FORM", "LEAD_MAGNET"
    private String businessId; // Business/Tenant UUID or ID string
    private String landingPageUrl;
}
