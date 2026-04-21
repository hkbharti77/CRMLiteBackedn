package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "bookings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id", nullable = false)
    private Lead lead;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    /** Service booked — e.g. "Hair Cut", "Yoga Session" */
    @Column(nullable = false)
    private String service;

    /** Preferred slot captured from flow */
    private String preferredSlot;

    /**
     * Full structured data from WhatsApp flow as JSON.
     * e.g. {"service":"Hair Cut","date_time":"Saturday 11AM","email":"x@x.com"}
     */
    @Column(name = "collected_data", columnDefinition = "text")
    @Builder.Default
    private String collectedData = "{}";

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private BookingStatus status = BookingStatus.CONFIRMED;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum BookingStatus {
        CONFIRMED, COMPLETED, CANCELLED, NO_SHOW
    }
}
