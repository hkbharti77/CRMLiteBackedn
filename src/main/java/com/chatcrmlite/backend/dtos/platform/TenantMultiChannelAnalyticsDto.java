package com.chatcrmlite.backend.dtos.platform;

import lombok.*;

import java.io.Serializable;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantMultiChannelAnalyticsDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private String range;
    private String timezone;
    private String fromDate;
    private String toDate;

    // Leads & Pipeline
    private LeadsMetrics leads;

    // Email Marketing Engine
    private EmailMetrics emails;

    // WhatsApp Cloud Messaging
    private WhatsAppMetrics whatsapp;

    // Support Desk
    private TicketMetrics tickets;

    // Appointments & Bookings
    private AppointmentMetrics appointments;

    // AI Knowledge Base
    private KnowledgeBaseMetrics knowledgeBase;

    // Quota Utilization & Health
    private QuotaHealthMetrics quotas;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LeadsMetrics implements Serializable {
        private int totalCreated;
        private int wonCount;
        private int lostCount;
        private int activeCount;
        private double conversionRate; // (won / total) * 100
        private Map<String, Integer> stageDistribution;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EmailMetrics implements Serializable {
        private int totalSent;
        private int delivered;
        private int opened;
        private int clicked;
        private int bounced;
        private int failed;
        private double deliveryRate; // (delivered / sent) * 100
        private double openRate;     // (opened / delivered) * 100
        private double bounceRate;   // (bounced / sent) * 100
        private int campaignsCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WhatsAppMetrics implements Serializable {
        private int campaignsCount;
        private int totalAttempted;
        private int totalSent;
        private int delivered;
        private int read;
        private int failed;
        private double deliveryRate;
        private double readRate;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TicketMetrics implements Serializable {
        private int totalTickets;
        private int openTickets;
        private int pendingTickets;
        private int resolvedTickets;
        private int closedTickets;
        private double resolutionRate;
        private double avgResolutionTimeHours;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AppointmentMetrics implements Serializable {
        private int totalBooked;
        private int completed;
        private int upcoming;
        private int cancelled;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class KnowledgeBaseMetrics implements Serializable {
        private int totalDocuments;
        private int readyDocuments;
        private int processingDocuments;
        private int failedDocuments;
        private int totalChunks;
        private String embeddingStatus;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class QuotaHealthMetrics implements Serializable {
        private QuotaItem employeeQuota;
        private QuotaItem leadQuota;
        private QuotaItem emailQuota;
        private QuotaItem whatsappQuota;
        private QuotaItem ticketQuota;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class QuotaItem implements Serializable {
        private String name;
        private int used;
        private int limit;
        private double percentage;
        private String healthStatus; // HEALTHY, WARNING, CRITICAL, EXHAUSTED, SERVICE_DISABLED
        private boolean serviceEnabled;
    }
}
