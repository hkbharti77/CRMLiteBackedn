package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Idempotency guard for incoming WhatsApp webhook messages.
 *
 * Before processing any incoming webhook, the WhatsApp service checks
 * this table to confirm the message hasn't already been handled.
 * This prevents duplicate Lead/Booking/Appointment records from
 * WhatsApp's at-least-once webhook delivery.
 */
@Entity
@Table(
    name = "processed_messages",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_processed_message_id",
        columnNames = "message_id"
    ),
    indexes = {
        @Index(name = "idx_processed_msg_owner",       columnList = "owner_id"),
        @Index(name = "idx_processed_msg_processed_at", columnList = "processed_at")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    /** WhatsApp message ID (wamid.xxx…). Guaranteed unique per message. */
    @Column(name = "message_id", nullable = false, unique = true, length = 255)
    private String messageId;

    @Column(name = "processed_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime processedAt = LocalDateTime.now();
}
