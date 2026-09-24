package com.chatcrmlite.backend.models.sms;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "sms_campaigns")
@Data
public class SmsCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "business_id", length = 50, nullable = false)
    private String businessId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "template_id")
    private UUID templateId;

    @Column(name = "provider_id", length = 50)
    private String providerId;

    @Column(length = 50)
    private String status = "DRAFT"; // DRAFT, SCHEDULED, PROCESSING, COMPLETED, FAILED

    @Column(name = "total_recipients")
    private Integer totalRecipients = 0;

    @Column(name = "delivered_count")
    private Integer deliveredCount = 0;

    @Column(name = "failed_count")
    private Integer failedCount = 0;

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
