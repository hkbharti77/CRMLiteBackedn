package com.chatcrmlite.backend.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_campaign_recipients", uniqueConstraints = {
    @UniqueConstraint(name = "uk_camp_phone", columnNames = {"campaign_id", "phone_number"})
}, indexes = {
    @Index(name = "idx_wa_cr_tenant", columnList = "tenant_id"),
    @Index(name = "idx_wa_cr_camp", columnList = "campaign_id"),
    @Index(name = "idx_wa_cr_status", columnList = "status"),
    @Index(name = "idx_wa_cr_wamid", columnList = "waMessageId")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class WhatsAppCampaignRecipient extends BaseTenantEntity {

    public enum RecipientStatus {
        PENDING,
        QUEUED,
        SENT,
        DELIVERED,
        READ,
        FAILED,
        SKIPPED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private WhatsAppCampaign campaign;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id", nullable = true, foreignKey = @ForeignKey(name = "fk_wa_camp_recip_contact"))
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.SET_NULL)
    private Contact contact;

    @Column(nullable = false)
    private String phoneNumber;

    @Column(columnDefinition = "TEXT")
    private String resolvedVariablesJson; // JSON payload of rendered parameters for this contact

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecipientStatus status;

    private String skipReason; // e.g., "OPTED_OUT", "INVALID_PHONE", "BLACK_LISTED"

    private String waMessageId;

    @Builder.Default
    private Integer retryCount = 0;

    private String errorMessage;

    private LocalDateTime sentAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        super.populateTenant();
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = RecipientStatus.PENDING;
        }
        if (retryCount == null) {
            retryCount = 0;
        }
    }
}
