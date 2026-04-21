package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tracks the multi-step WhatsApp conversation flow for each contact.
 * One active state per contact at a time.
 */
@Entity
@Table(name = "conversation_states")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ── Contact whose flow is being tracked ─────────────────────────────────
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id", nullable = false, unique = true)
    private Contact contact;

    // ── Flow metadata ────────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FlowType flowType;   // APPOINTMENT, BOOKING, ENQUIRY, LEAD_CAPTURE

    @Column(nullable = false)
    @Builder.Default
    private Integer currentStep = 0;  // 0-indexed step within the flow

    // ── Collected answers stored as compact JSON ─────────────────────────────
    // e.g.  {"service":"Hair Cut","date":"Tomorrow","time":"10 AM","email":"r@r.com"}
    @Column(columnDefinition = "text")
    @Builder.Default
    private String collectedData = "{}";

    // ── Timestamps ───────────────────────────────────────────────────────────
    @Builder.Default
    private LocalDateTime startedAt = LocalDateTime.now();

    @Builder.Default
    private LocalDateTime lastUpdatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        lastUpdatedAt = LocalDateTime.now();
    }

    // ── Flow Types ───────────────────────────────────────────────────────────
    public enum FlowType {
        APPOINTMENT,    // Health: Dental / Physiotherapy (needs Date + Time => creates Appointment)
        BOOKING,        // Salon / Gym / Yoga            (needs Slot       => sets Lead BOOKED)
        ENQUIRY,        // Education / Tutors             (needs Subject    => sets Lead FOLLOW_UP)
        LEAD_CAPTURE    // Real Estate / Freelancers      (needs Budget etc => sets Lead INTERESTED)
    }
}
