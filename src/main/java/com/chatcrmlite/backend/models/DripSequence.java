package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "drip_sequences", indexes = {
    @Index(name = "idx_ds_tenant", columnList = "tenant_id"),
    @Index(name = "idx_ds_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DripSequence extends BaseTenantEntity {

    public enum TriggerEvent {
        LEAD_CREATED,
        TAG_ADDED,
        FORM_SUBMITTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TriggerEvent triggerEvent;

    @Column(columnDefinition = "TEXT")
    private String triggerConditionJson; // e.g., {"leadStatus": "NEW"}

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        super.populateTenant();
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (active == null) {
            active = true;
        }
    }
}
