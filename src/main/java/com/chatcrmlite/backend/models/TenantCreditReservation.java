package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tenant_credit_reservations", indexes = {
    @Index(name = "idx_credit_reservations_tenant_status", columnList = "tenant_id, status"),
    @Index(name = "idx_credit_reservations_expires", columnList = "status, expires_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantCreditReservation implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum ReservationStatus {
        PENDING,
        SETTLED,
        RELEASED,
        EXPIRED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private ReservationStatus status = ReservationStatus.PENDING;

    @Column(name = "reference_id", length = 100)
    private String referenceId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "settled_at")
    private LocalDateTime settledAt;
}
