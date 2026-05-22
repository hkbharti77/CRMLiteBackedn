package com.chatcrmlite.backend.models;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tickets")
public class Ticket extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ticket_number", unique = true, nullable = false)
    private String ticketNumber;

    /**
     * Human-readable ticket reference number.
     * Format: {PREFIX}-{TYPE}-{YYYYMMDD}-{NNNN}
     * Example: GYAN-T-20250520-0001
     * Generated on first save; unique per owner.
     */
    @Column(name = "reference_number", unique = false, updatable = false)
    private String referenceNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id")
    private Contact contact;

    @Column(nullable = false)
    private String subject;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    private String submitterName;
    private String submitterEmail;
    private String submitterPhone;

    @Enumerated(EnumType.STRING)
    private TicketStatus status = TicketStatus.OPEN;

    @Enumerated(EnumType.STRING)
    private TicketPriority priority = TicketPriority.MEDIUM;

    @Enumerated(EnumType.STRING)
    private TicketSource source = TicketSource.MANUAL;

    private String category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_id")
    private User assignedTo;

    private LocalDateTime firstResponseDueAt;
    private LocalDateTime resolutionDueAt;
    private LocalDateTime firstRespondedAt;

    @Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean slaBreached = false;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt;
    private LocalDateTime resolvedAt;

    @Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean deleted = false;

    private LocalDateTime deletedAt;
    private UUID deletedBy;

    public Ticket() {}

    public Ticket(UUID id, String ticketNumber, String referenceNumber, User owner, Contact contact, String subject, String description, String submitterName, String submitterEmail, String submitterPhone, TicketStatus status, TicketPriority priority, TicketSource source, String category, User assignedTo, LocalDateTime firstResponseDueAt, LocalDateTime resolutionDueAt, LocalDateTime firstRespondedAt, boolean slaBreached, LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime resolvedAt, boolean deleted, LocalDateTime deletedAt, UUID deletedBy) {
        this.id = id;
        this.ticketNumber = ticketNumber;
        this.referenceNumber = referenceNumber;
        this.owner = owner;
        this.contact = contact;
        this.subject = subject;
        this.description = description;
        this.submitterName = submitterName;
        this.submitterEmail = submitterEmail;
        this.submitterPhone = submitterPhone;
        this.status = (status != null) ? status : TicketStatus.OPEN;
        this.priority = (priority != null) ? priority : TicketPriority.MEDIUM;
        this.source = (source != null) ? source : TicketSource.MANUAL;
        this.category = category;
        this.assignedTo = assignedTo;
        this.firstResponseDueAt = firstResponseDueAt;
        this.resolutionDueAt = resolutionDueAt;
        this.firstRespondedAt = firstRespondedAt;
        this.slaBreached = slaBreached;
        this.createdAt = (createdAt != null) ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt;
        this.resolvedAt = resolvedAt;
        this.deleted = deleted;
        this.deletedAt = deletedAt;
        this.deletedBy = deletedBy;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTicketNumber() { return ticketNumber; }
    public void setTicketNumber(String ticketNumber) { this.ticketNumber = ticketNumber; }
    public String getReferenceNumber() { return referenceNumber; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; }
    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
    public Contact getContact() { return contact; }
    public void setContact(Contact contact) { this.contact = contact; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSubmitterName() { return submitterName; }
    public void setSubmitterName(String submitterName) { this.submitterName = submitterName; }
    public String getSubmitterEmail() { return submitterEmail; }
    public void setSubmitterEmail(String submitterEmail) { this.submitterEmail = submitterEmail; }
    public String getSubmitterPhone() { return submitterPhone; }
    public void setSubmitterPhone(String submitterPhone) { this.submitterPhone = submitterPhone; }
    public TicketStatus getStatus() { return status; }
    public void setStatus(TicketStatus status) { this.status = status; }
    public TicketPriority getPriority() { return priority; }
    public void setPriority(TicketPriority priority) { this.priority = priority; }
    public TicketSource getSource() { return source; }
    public void setSource(TicketSource source) { this.source = source; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public User getAssignedTo() { return assignedTo; }
    public void setAssignedTo(User assignedTo) { this.assignedTo = assignedTo; }
    public LocalDateTime getFirstResponseDueAt() { return firstResponseDueAt; }
    public void setFirstResponseDueAt(LocalDateTime firstResponseDueAt) { this.firstResponseDueAt = firstResponseDueAt; }
    public LocalDateTime getResolutionDueAt() { return resolutionDueAt; }
    public void setResolutionDueAt(LocalDateTime resolutionDueAt) { this.resolutionDueAt = resolutionDueAt; }
    public LocalDateTime getFirstRespondedAt() { return firstRespondedAt; }
    public void setFirstRespondedAt(LocalDateTime firstRespondedAt) { this.firstRespondedAt = firstRespondedAt; }
    public boolean isSlaBreached() { return slaBreached; }
    public void setSlaBreached(boolean slaBreached) { this.slaBreached = slaBreached; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public UUID getDeletedBy() { return deletedBy; }
    public void setDeletedBy(UUID deletedBy) { this.deletedBy = deletedBy; }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum TicketStatus {
        OPEN, IN_PROGRESS, WAITING_FOR_CUSTOMER, RESOLVED, CLOSED
    }

    public enum TicketPriority {
        LOW, MEDIUM, HIGH, URGENT
    }

    public enum TicketSource {
        MANUAL, SUPPORT_FORM, WHATSAPP, EMAIL
    }

    public static TicketBuilder builder() { return new TicketBuilder(); }

    public static class TicketBuilder {
        private UUID id;
        private String ticketNumber;
        private String referenceNumber;
        private User owner;
        private Contact contact;
        private String subject;
        private String description;
        private String submitterName;
        private String submitterEmail;
        private String submitterPhone;
        private TicketStatus status = TicketStatus.OPEN;
        private TicketPriority priority = TicketPriority.MEDIUM;
        private TicketSource source = TicketSource.MANUAL;
        private String category;
        private User assignedTo;
        private LocalDateTime firstResponseDueAt;
        private LocalDateTime resolutionDueAt;
        private LocalDateTime firstRespondedAt;
        private boolean slaBreached = false;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime updatedAt;
        private LocalDateTime resolvedAt;
        private boolean deleted = false;
        private LocalDateTime deletedAt;
        private UUID deletedBy;

        public TicketBuilder id(UUID id) { this.id = id; return this; }
        public TicketBuilder ticketNumber(String ticketNumber) { this.ticketNumber = ticketNumber; return this; }
        public TicketBuilder referenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; return this; }
        public TicketBuilder owner(User owner) { this.owner = owner; return this; }
        public TicketBuilder contact(Contact contact) { this.contact = contact; return this; }
        public TicketBuilder subject(String subject) { this.subject = subject; return this; }
        public TicketBuilder description(String description) { this.description = description; return this; }
        public TicketBuilder submitterName(String submitterName) { this.submitterName = submitterName; return this; }
        public TicketBuilder submitterEmail(String submitterEmail) { this.submitterEmail = submitterEmail; return this; }
        public TicketBuilder submitterPhone(String submitterPhone) { this.submitterPhone = submitterPhone; return this; }
        public TicketBuilder status(TicketStatus status) { this.status = status; return this; }
        public TicketBuilder priority(TicketPriority priority) { this.priority = priority; return this; }
        public TicketBuilder source(TicketSource source) { this.source = source; return this; }
        public TicketBuilder category(String category) { this.category = category; return this; }
        public TicketBuilder assignedTo(User assignedTo) { this.assignedTo = assignedTo; return this; }
        public TicketBuilder firstResponseDueAt(LocalDateTime firstResponseDueAt) { this.firstResponseDueAt = firstResponseDueAt; return this; }
        public TicketBuilder resolutionDueAt(LocalDateTime resolutionDueAt) { this.resolutionDueAt = resolutionDueAt; return this; }
        public TicketBuilder firstRespondedAt(LocalDateTime firstRespondedAt) { this.firstRespondedAt = firstRespondedAt; return this; }
        public TicketBuilder slaBreached(boolean slaBreached) { this.slaBreached = slaBreached; return this; }
        public TicketBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public TicketBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public TicketBuilder resolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; return this; }
        public TicketBuilder deleted(boolean deleted) { this.deleted = deleted; return this; }
        public TicketBuilder deletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; return this; }
        public TicketBuilder deletedBy(UUID deletedBy) { this.deletedBy = deletedBy; return this; }

        public Ticket build() {
            return new Ticket(id, ticketNumber, referenceNumber, owner, contact, subject, description, submitterName, submitterEmail, submitterPhone, status, priority, source, category, assignedTo, firstResponseDueAt, resolutionDueAt, firstRespondedAt, slaBreached, createdAt, updatedAt, resolvedAt, deleted, deletedAt, deletedBy);
        }
    }
}
