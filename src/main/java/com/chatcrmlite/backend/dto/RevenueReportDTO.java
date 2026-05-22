package com.chatcrmlite.backend.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import lombok.extern.jackson.Jacksonized;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@NoArgsConstructor
@AllArgsConstructor
public class RevenueReportDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private BigDecimal totalPipelineValue;  // Sum of ALL deal values
    private BigDecimal receivedRevenue;     // Sum of PAID deal values
    private BigDecimal pendingRevenue;      // Sum of PENDING + PARTIAL deal values
    private long totalDeals;               // Total leads with a deal value set
    private long paidDeals;                // Leads marked as PAID
    private long pendingDeals;             // Leads marked as PENDING or PARTIAL
    private String currency;               // "INR"
}
