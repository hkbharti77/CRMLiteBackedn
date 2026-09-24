package com.chatcrmlite.backend.models.journey;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "customer_journey_versions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerJourneyVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "journey_id", nullable = false)
    private UUID journeyId;

    @Column(name = "business_id", nullable = false, length = 50)
    private String businessId;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "definition_json", nullable = false, columnDefinition = "jsonb")
    private String definitionJson;

    @Builder.Default
    @Column(name = "status", length = 50)
    private String status = "DRAFT";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt;

    @Column(name = "published_at")
    private ZonedDateTime publishedAt;
}
