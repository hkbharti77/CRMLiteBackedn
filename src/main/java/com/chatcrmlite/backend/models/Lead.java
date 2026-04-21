package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "leads")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Lead {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id")
    private Contact contact;

    @Enumerated(EnumType.STRING)
    private LeadStatus status;

    /**
     * Enquiries stored as a JSON array string.
     * Each element: { "id": "uuid", "type": "WHATSAPP|MANUAL|AI", "message": "...",
     *                 "source": "...", "status": "OPEN|RESOLVED|FOLLOW_UP",
     *                 "createdAt": "ISO-datetime" }
     */
    @Column(columnDefinition = "text")
    @Builder.Default
    private String enquiries = "[]";

    @Column(name = "deleted", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    @Builder.Default
    private boolean deleted = false;

    @ElementCollection
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder.Default
    private LocalDateTime lastActivity = LocalDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    // ── Deal / Payment Tracking ────────────────────────────────────────────
    private BigDecimal dealValue;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PaymentStatus paymentStatus = PaymentStatus.NONE;

    @Builder.Default
    private String currency = "INR";

    private String dealLabel;

    public enum PaymentStatus {
        NONE, PENDING, PARTIAL, PAID
    }

    public enum LeadStatus {
        NEW, INTERESTED, FOLLOW_UP, BOOKED, CLOSED_WON, CLOSED_LOST
    }
}
