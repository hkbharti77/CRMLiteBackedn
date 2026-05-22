package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Relational replacement for Lead.enquiries JSON blob (AP-1 fix).
 *
 * Each row is one enquiry attached to a Lead. FK constraint guarantees
 * referential integrity. The old JSON TEXT column is preserved as a
 * deprecated field during migration, then dropped in a later migration.
 *
 * Concurrent writes are now safe: two transactions writing to the same lead
 * insert independent rows rather than racing to overwrite a single JSON string.
 */
@Entity
@Table(
    name = "lead_enquiries",
    indexes = {
        @Index(name = "idx_le_lead_id",      columnList = "lead_id"),
        @Index(name = "idx_le_lead_created", columnList = "lead_id, created_at DESC")
    }
)
public class LeadEnquiry implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lead_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_lead_enquiry_lead"))
    private Lead lead;

    /**
     * Enquiry type: MANUAL | WHATSAPP | FORM | AUTO
     */
    @Column(name = "type", nullable = false, length = 50)
    private String type = "MANUAL";

    @Column(name = "message", columnDefinition = "TEXT", nullable = false)
    private String message;

    /**
     * Source context: "Manual Entry" | "WhatsApp" | "Support Form" etc.
     */
    @Column(name = "source", length = 255)
    private String source = "Manual Entry";

    /**
     * Resolution state: OPEN | IN_PROGRESS | RESOLVED | CLOSED
     */
    @Column(name = "status", length = 30, nullable = false)
    private String status = "OPEN";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public LeadEnquiry() {}

    public LeadEnquiry(Lead lead, String type, String message, String source, String status) {
        this.lead = lead;
        this.type = type != null ? type : "MANUAL";
        this.message = message;
        this.source = source != null ? source : "Manual Entry";
        this.status = status != null ? status : "OPEN";
    }

    // ── Getters / Setters ────────────────────────────────────────────────

    public UUID getId()                      { return id; }
    public Lead getLead()                    { return lead; }
    public String getType()                  { return type; }
    public String getMessage()               { return message; }
    public String getSource()                { return source; }
    public String getStatus()                { return status; }
    public LocalDateTime getCreatedAt()      { return createdAt; }
    public LocalDateTime getUpdatedAt()      { return updatedAt; }

    public void setId(UUID id)               { this.id = id; }
    public void setLead(Lead lead)           { this.lead = lead; }
    public void setType(String type)         { this.type = type; }
    public void setMessage(String message)   { this.message = message; }
    public void setSource(String source)     { this.source = source; }
    public void setStatus(String status)     { this.status = status; }
    public void setCreatedAt(LocalDateTime t){ this.createdAt = t; }
    public void setUpdatedAt(LocalDateTime t){ this.updatedAt = t; }

    // ── Builder ──────────────────────────────────────────────────────────

    public static LeadEnquiryBuilder builder() { return new LeadEnquiryBuilder(); }

    public static class LeadEnquiryBuilder {
        private Lead lead;
        private String type = "MANUAL";
        private String message;
        private String source = "Manual Entry";
        private String status = "OPEN";

        public LeadEnquiryBuilder lead(Lead lead)       { this.lead = lead;     return this; }
        public LeadEnquiryBuilder type(String type)     { this.type = type;     return this; }
        public LeadEnquiryBuilder message(String msg)   { this.message = msg;   return this; }
        public LeadEnquiryBuilder source(String src)    { this.source = src;    return this; }
        public LeadEnquiryBuilder status(String status) { this.status = status; return this; }

        public LeadEnquiry build() {
            return new LeadEnquiry(lead, type, message, source, status);
        }
    }
}
