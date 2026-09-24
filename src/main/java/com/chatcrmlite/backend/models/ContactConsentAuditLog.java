package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "contact_consent_audit_logs", indexes = {
    @Index(name = "idx_consent_audit_contact", columnList = "contact_id"),
    @Index(name = "idx_consent_audit_tenant", columnList = "tenant_id"),
    @Index(name = "idx_consent_audit_channel", columnList = "channel")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContactConsentAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "contact_id", nullable = false)
    private UUID contactId;

    @Column(name = "channel", nullable = false, length = 20)
    private String channel; // WHATSAPP, EMAIL, SMS, GLOBAL

    @Column(name = "previous_status", length = 20)
    private String previousStatus; // OPTED_IN, OPTED_OUT, UNKNOWN

    @Column(name = "new_status", nullable = false, length = 20)
    private String newStatus; // OPTED_IN, OPTED_OUT, UNKNOWN

    @Column(name = "source", nullable = false, length = 50)
    private String source; // ADMIN_MANUAL, WEB_WIDGET, PUBLIC_FORM, WHATSAPP_WEBHOOK, EMAIL_UNSUBSCRIBE, SMS_KEYWORD

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "performed_by", length = 100)
    private String performedBy; // Email or User ID if authenticated

    @Builder.Default
    @Column(name = "timestamp", nullable = false)
    private Instant timestamp = Instant.now();
}
