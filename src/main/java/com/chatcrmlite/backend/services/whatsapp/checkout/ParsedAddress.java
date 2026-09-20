package com.chatcrmlite.backend.services.whatsapp.checkout;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedAddress {
    private String shippingName;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String postalCode;
    @Builder.Default
    private String country = "IN";
    private String customerEmail;
    private String fullFormattedAddress;

    @Builder.Default
    private boolean valid = false;

    @Builder.Default
    private List<String> missingFields = new ArrayList<>();

    private boolean hasOnlyEmail;
    private boolean hasOnlyAddress;
}
