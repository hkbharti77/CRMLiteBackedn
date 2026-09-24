package com.chatcrmlite.backend.models.sms;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "sms_templates")
@Data
public class SmsTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "business_id", length = 50, nullable = false)
    private String businessId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(length = 50)
    private String category = "TRANSACTIONAL"; // TRANSACTIONAL, PROMOTIONAL, OTP

    @Column(name = "country_code", length = 10)
    private String countryCode = "IN";

    @Column(name = "sender_id", length = 50)
    private String senderId;

    @Column(name = "dlt_entity_id", length = 100)
    private String dltEntityId;

    @Column(name = "dlt_template_id", length = 100)
    private String dltTemplateId;

    @Column(name = "dlt_header_id", length = 100)
    private String dltHeaderId;

    @Column(name = "allowed_variables", columnDefinition = "jsonb")
    private String allowedVariables; // ["lead_name", "booking_time"]

    @Column(length = 50)
    private String status = "APPROVED"; // PENDING, APPROVED, REJECTED

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
