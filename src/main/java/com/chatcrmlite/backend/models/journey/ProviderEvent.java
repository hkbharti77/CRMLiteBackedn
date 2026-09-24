package com.chatcrmlite.backend.models.journey;

import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "provider_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "business_id", nullable = false, length = 50)
    private String businessId;

    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    @Column(name = "provider_event_id", nullable = false, length = 255)
    private String providerEventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "received_at")
    private ZonedDateTime receivedAt;

    @Column(name = "processed_at")
    private ZonedDateTime processedAt;
}
