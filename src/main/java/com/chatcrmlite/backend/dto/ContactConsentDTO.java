package com.chatcrmlite.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContactConsentDTO {

    private UUID contactId;
    private String contactName;
    private String phone;
    private String email;

    // Channel Tri-State Statuses: OPTED_IN, OPTED_OUT, UNKNOWN
    private String whatsappConsentStatus;
    private boolean whatsappAllowed; // Convenience boolean (true if OPTED_IN or UNKNOWN unless globally suppressed)

    private String emailConsentStatus;
    private boolean emailAllowed;

    private String smsConsentStatus;
    private boolean smsAllowed;

    private boolean globallySuppressed;
    private boolean marketingOptedOut;

    private Instant updatedAt;
    private String lastSource;
    private List<AuditLogEntryDTO> recentAuditLogs;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditLogEntryDTO {
        private UUID id;
        private String channel;
        private String previousStatus;
        private String newStatus;
        private String source;
        private String reason;
        private String performedBy;
        private Instant timestamp;
    }
}
