package com.chatcrmlite.backend.models.sms;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "sms_campaign_recipients", uniqueConstraints = {
    @UniqueConstraint(name = "uq_sms_campaign_recipient", columnNames = {"campaign_id", "phone_number"})
})
@Data
public class SmsCampaignRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "contact_id")
    private UUID contactId;

    @Column(name = "phone_number", length = 30, nullable = false)
    private String phoneNumber;

    @Column(length = 50)
    private String status = "PENDING"; // PENDING, QUEUED, SENT, DELIVERED, FAILED, SUPPRESSED

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    private Integer segments = 1;

    @Column(name = "unit_cost", precision = 12, scale = 6)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(name = "total_cost", precision = 12, scale = 6)
    private BigDecimal totalCost = BigDecimal.ZERO;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;
}
