package com.chatcrmlite.backend.dtos.platform;

import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantProfileSummaryDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private UUID id;
    private String businessName;
    private String businessType;
    private String businessSubType;
    private String address;
    private String aboutUs;
    private String logoUrl;
    private String primaryColor;
    private String secondaryColor;
    private String country;
    private String currency;
    private String timezone;
    private String planType;
    private String planName;
    private String lifecycleStatus;
    private String suspensionReason;
    private LocalDateTime suspendedAt;
    private Boolean onboardingCompleted;
    private LocalDateTime createdAt;

    // Subscription & Billing details
    private String billingCycle;
    private String subscriptionStatus;
    private LocalDateTime currentPeriodStart;
    private LocalDateTime currentPeriodEnd;
    private BigDecimal monthlyAmount;

    // Summary Tallies
    private int totalUsers;
    private int activeUsers;
    private int totalLeads;
    private int totalTickets;
    private int totalAppointments;
}
