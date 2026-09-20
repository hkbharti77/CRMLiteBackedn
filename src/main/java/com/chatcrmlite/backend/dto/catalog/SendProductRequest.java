package com.chatcrmlite.backend.dto.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SendProductRequest {
    @NotBlank(message = "requestId is required")
    private String requestId;

    @NotBlank(message = "waId is required")
    @Pattern(regexp = "^\\+?[1-9]\\d{9,14}$", message = "waId must be a valid phone number with 10 to 15 digits")
    private String waId;

    @NotBlank(message = "productRetailerId is required")
    private String productRetailerId;

    private String bodyText;
}
