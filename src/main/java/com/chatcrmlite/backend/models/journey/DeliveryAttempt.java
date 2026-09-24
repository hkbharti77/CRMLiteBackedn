package com.chatcrmlite.backend.models.journey;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "delivery_attempts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "dispatch_id", nullable = false)
    private UUID dispatchId;

    @Column(name = "business_id", nullable = false, length = 50)
    private String businessId;

    @Column(name = "attempt_no", nullable = false)
    private Integer attemptNo;

    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Column(name = "status", nullable = false, length = 50)
    private String status;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt;
}
