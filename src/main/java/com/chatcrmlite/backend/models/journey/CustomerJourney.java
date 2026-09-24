package com.chatcrmlite.backend.models.journey;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "customer_journeys")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerJourney {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "business_id", nullable = false, length = 50)
    private String businessId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "trigger_event", nullable = false, length = 100)
    private String triggerEvent;

    @Builder.Default
    @Column(name = "status", length = 50)
    private String status = "DRAFT";

    @Builder.Default
    @Column(name = "reentry_mode", length = 50)
    private String reentryMode = "ONE_ACTIVE_PER_CONTACT";

    @Column(name = "published_version_id")
    private UUID publishedVersionId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private ZonedDateTime updatedAt;
}
