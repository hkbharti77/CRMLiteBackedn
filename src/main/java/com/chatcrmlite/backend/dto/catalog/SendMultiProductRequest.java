package com.chatcrmlite.backend.dto.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SendMultiProductRequest {
    @NotBlank(message = "requestId is required")
    private String requestId;

    @NotBlank(message = "waId is required")
    @Pattern(regexp = "^\\+?[1-9]\\d{9,14}$", message = "waId must be a valid phone number with 10 to 15 digits")
    private String waId;

    private String headerText;
    private String bodyText;
    private String footerText;

    @NotEmpty(message = "At least one productRetailerId is required")
    private List<String> productRetailerIds;
}
