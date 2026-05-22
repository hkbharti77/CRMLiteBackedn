package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Unified CRM Timeline Entry.
 *
 * Stores a flattened, immutable record of every significant CRM action
 * across all modules (Lead, Booking, Appointment) without coupling them together.
 *
 * This table is write-once and should never be updated — only appended.
 * It acts as the single source of truth for "what happened to this contact".
 */
@Entity
@Table(
    name = "activity_logs",
    indexes = {
        @Index(name = "idx_activity_contact_id", columnList = "contact_id"),
        @Index(name = "idx_activity_owner_id",   columnList = "owner_id"),
        @Index(name = "idx_activity_entity",      columnList = "entity_type, entity_id"),
        @Index(name = "idx_activity_created_at",  columnList = "created_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ── Ownership ──────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id", nullable = false)
    private Contact contact;

    // ── Domain Reference ────────────────────────────────────────────────────

    /** "LEAD" | "BOOKING" | "APPOINTMENT" | "CONTACT" */
    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    /** UUID of the Lead / Booking / Appointment that triggered this log */
    @Column(name = "entity_id")
    private UUID entityId;

    // ── Activity Detail ─────────────────────────────────────────────────────

    /**
     * Specific event code. Examples:
     *   LEAD_CREATED, LEAD_STATUS_CHANGED,
     *   BOOKING_CONFIRMED, BOOKING_CANCELLED,
     *   APPOINTMENT_SCHEDULED, APPOINTMENT_COMPLETED
     */
    @Column(name = "activity_type", nullable = false, length = 100)
    private String activityType;

    /**
     * Source of activity: "FLOW" | "MANUAL" | "API" | "SYSTEM"
     */
    @Column(name = "source", length = 50)
    @Builder.Default
    private String source = "SYSTEM";

    /**
     * Human-readable summary (max 500 chars) for the CRM timeline UI.
     * Example: "Booking confirmed for Hair Cut on Saturday 11AM"
     */
    @Column(name = "summary", length = 500)
    private String summary;

    /**
     * Optional JSON payload for rich detail / future analytics.
     * Example: {"service":"Hair Cut","slot":"Saturday 11AM","status":"CONFIRMED"}
     */
    @Column(name = "payload", columnDefinition = "text")
    private String payload;

    // ── Timestamp ──────────────────────────────────────────────────────────

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    // ── Activity Type Constants ─────────────────────────────────────────────

    public static final String LEAD_CREATED             = "LEAD_CREATED";
    public static final String LEAD_STATUS_CHANGED      = "LEAD_STATUS_CHANGED";
    public static final String LEAD_ENQUIRY_ADDED       = "LEAD_ENQUIRY_ADDED";
    public static final String BOOKING_CONFIRMED        = "BOOKING_CONFIRMED";
    public static final String BOOKING_CANCELLED        = "BOOKING_CANCELLED";
    public static final String BOOKING_COMPLETED        = "BOOKING_COMPLETED";
    public static final String BOOKING_NO_SHOW          = "BOOKING_NO_SHOW";
    public static final String APPOINTMENT_SCHEDULED    = "APPOINTMENT_SCHEDULED";
    public static final String APPOINTMENT_CANCELLED    = "APPOINTMENT_CANCELLED";
    public static final String APPOINTMENT_COMPLETED    = "APPOINTMENT_COMPLETED";
    public static final String APPOINTMENT_NO_SHOW      = "APPOINTMENT_NO_SHOW";

    // ── Entity Type Constants ───────────────────────────────────────────────

    public static final String TYPE_LEAD        = "LEAD";
    public static final String TYPE_BOOKING     = "BOOKING";
    public static final String TYPE_APPOINTMENT = "APPOINTMENT";
    public static final String TYPE_CONTACT     = "CONTACT";
    public static ActivityLogBuilder builder() {
        return new ActivityLogBuilder();
    }

    public static class ActivityLogBuilder {
        private User owner;
        private Contact contact;
        private String entityType;
        private UUID entityId;
        private String activityType;
        private String source;
        private String summary;
        private String payload;

        public ActivityLogBuilder owner(User owner) { this.owner = owner; return this; }
        public ActivityLogBuilder contact(Contact contact) { this.contact = contact; return this; }
        public ActivityLogBuilder entityType(String entityType) { this.entityType = entityType; return this; }
        public ActivityLogBuilder entityId(UUID entityId) { this.entityId = entityId; return this; }
        public ActivityLogBuilder activityType(String activityType) { this.activityType = activityType; return this; }
        public ActivityLogBuilder source(String source) { this.source = source; return this; }
        public ActivityLogBuilder summary(String summary) { this.summary = summary; return this; }
        public ActivityLogBuilder payload(String payload) { this.payload = payload; return this; }

        public ActivityLog build() {
            ActivityLog log = new ActivityLog();
            log.setOwner(owner);
            log.setContact(contact);
            log.setEntityType(entityType);
            log.setEntityId(entityId);
            log.setActivityType(activityType);
            log.setSource(source != null ? source : "SYSTEM");
            log.setSummary(summary);
            log.setPayload(payload);
            return log;
        }
    }

    public void setOwner(User owner) { this.owner = owner; }
    public void setContact(Contact contact) { this.contact = contact; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }
    public void setActivityType(String activityType) { this.activityType = activityType; }
    public void setSource(String source) { this.source = source; }
    public void setSummary(String summary) { this.summary = summary; }
    public void setPayload(String payload) { this.payload = payload; }
}
