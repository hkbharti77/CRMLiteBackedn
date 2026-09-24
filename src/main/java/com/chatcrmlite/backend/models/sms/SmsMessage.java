package com.chatcrmlite.backend.models.sms;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "sms_messages")
@Data
public class SmsMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "business_id", length = 50, nullable = false)
    private String businessId;

    @Column(name = "contact_id")
    private UUID contactId;

    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "campaign_id")
    private UUID campaignId;

    @Column(name = "campaign_recipient_id")
    private UUID campaignRecipientId;

    @Column(name = "provider_id", length = 50)
    private String providerId;

    @Column(name = "provider_type", length = 50, nullable = false)
    private String providerType;

    @Column(name = "provider_message_id", length = 255) // Nullable initially for QUEUED state
    private String providerMessageId;

    @Column(length = 20, nullable = false)
    private String direction; // INCOMING, OUTGOING

    @Column(name = "phone_number", length = 30, nullable = false)
    private String phoneNumber;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(length = 20)
    private String encoding = "GSM-7";

    private Integer segments = 1;

    @Column(name = "delivery_status", length = 50)
    private String deliveryStatus = "QUEUED"; // QUEUED, SENT, DELIVERED, FAILED

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "unit_cost", precision = 12, scale = 6)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(name = "total_cost", precision = 12, scale = 6)
    private BigDecimal totalCost = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
