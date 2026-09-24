package com.chatcrmlite.backend.models.sms;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "sms_delivery_events")
@Data
public class SmsDeliveryEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "provider_type", length = 50, nullable = false)
    private String providerType;

    @Column(name = "provider_message_id", length = 255, nullable = false)
    private String providerMessageId;

    @Column(name = "provider_event_id", length = 255)
    private String providerEventId;

    @Column(name = "event_type", length = 50, nullable = false)
    private String eventType; // QUEUED, SENT, DELIVERED, UNDELIVERED, FAILED

    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private String rawPayload;

    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;

    @CreationTimestamp
    @Column(name = "received_at", updatable = false)
    private LocalDateTime receivedAt;
}
