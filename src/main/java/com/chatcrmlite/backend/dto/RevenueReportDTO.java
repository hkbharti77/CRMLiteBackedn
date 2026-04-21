package com.chatcrmlite.backend.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class RevenueReportDTO {
    private BigDecimal totalPipelineValue;  // Sum of ALL deal values
    private BigDecimal receivedRevenue;     // Sum of PAID deal values
    private BigDecimal pendingRevenue;      // Sum of PENDING + PARTIAL deal values
    private long totalDeals;               // Total leads with a deal value set
    private long paidDeals;                // Leads marked as PAID
    private long pendingDeals;             // Leads marked as PENDING or PARTIAL
    private String currency;               // "INR"
}
