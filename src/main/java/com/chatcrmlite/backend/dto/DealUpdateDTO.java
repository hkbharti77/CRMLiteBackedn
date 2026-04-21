package com.chatcrmlite.backend.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class DealUpdateDTO {
    private BigDecimal dealValue;     // e.g. 5000.00
    private String paymentStatus;     // "NONE" | "PENDING" | "PARTIAL" | "PAID"
    private String currency;          // e.g. "INR"
    private String dealLabel;         // e.g. "Website Design Package"
}
