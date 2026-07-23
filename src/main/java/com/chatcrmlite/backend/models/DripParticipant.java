package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "drip_participants", indexes = {
    @Index(name = "idx_dp_tenant", columnList = "tenant_id"),
    @Index(name = "idx_dp_seq", columnList = "sequence_id"),
    @Index(name = "idx_dp_status", columnList = "status"),
    @Index(name = "idx_dp_next_run", columnList = "next_run_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DripParticipant extends BaseTenantEntity {

    public enum ParticipantStatus {
        ACTIVE,
        COMPLETED,
        EXITED_REPLIED,
        EXITED_STATUS_CHANGED,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sequence_id", nullable = false)
    private DripSequence sequence;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id", nullable = false)
    private Contact contact;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id")
    private Lead lead;

    @Builder.Default
    private Integer currentStepOrder = 1;

    private LocalDateTime nextRunAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ParticipantStatus status;

    private LocalDateTime enrolledAt;
    private LocalDateTime completedAt;

    @PrePersist
    public void prePersist() {
        super.populateTenant();
        if (enrolledAt == null) {
            enrolledAt = LocalDateTime.now();
        }
        if (status == null) {
            status = ParticipantStatus.ACTIVE;
        }
        if (currentStepOrder == null) {
            currentStepOrder = 1;
        }
    }
}
