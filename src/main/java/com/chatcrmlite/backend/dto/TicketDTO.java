package com.chatcrmlite.backend.dto;

import com.chatcrmlite.backend.models.Ticket;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import java.io.Serializable;

public class TicketDTO implements Serializable {
    private static final long serialVersionUID = 1L;


    private UUID id;
    private String referenceNumber;
    private String ticketNumber;
    private UUID contactId;
    private String contactName;
    private String contactWaId;
    private String submitterName;
    private String submitterEmail;
    private String submitterPhone;
    private String subject;
    private String description;
    private Ticket.TicketStatus status;
    private Ticket.TicketPriority priority;
    private Ticket.TicketSource source;
    private String category;
    private UUID assignedToId;
    private String assignedToName;
    private List<TicketCommentDTO> comments = new ArrayList<>();
    private String slaStatus;
    private boolean slaBreached;
    private LocalDateTime firstResponseDueAt;
    private LocalDateTime resolutionDueAt;
    private LocalDateTime firstRespondedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime resolvedAt;
    private boolean isNew;
    private String createdAtHuman;

    public TicketDTO() {}

    public TicketDTO(UUID id, String referenceNumber, String ticketNumber, UUID contactId, String contactName, String contactWaId, String submitterName, String submitterEmail, String submitterPhone, String subject, String description, Ticket.TicketStatus status, Ticket.TicketPriority priority, Ticket.TicketSource source, String category, UUID assignedToId, String assignedToName, List<TicketCommentDTO> comments, String slaStatus, boolean slaBreached, LocalDateTime firstResponseDueAt, LocalDateTime resolutionDueAt, LocalDateTime firstRespondedAt, LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime resolvedAt, boolean isNew, String createdAtHuman) {
        this.id = id;
        this.referenceNumber = referenceNumber;
        this.ticketNumber = ticketNumber;
        this.contactId = contactId;
        this.contactName = contactName;
        this.contactWaId = contactWaId;
        this.submitterName = submitterName;
        this.submitterEmail = submitterEmail;
        this.submitterPhone = submitterPhone;
        this.subject = subject;
        this.description = description;
        this.status = status;
        this.priority = priority;
        this.source = source;
        this.category = category;
        this.assignedToId = assignedToId;
        this.assignedToName = assignedToName;
        this.comments = (comments != null) ? comments : new ArrayList<>();
        this.slaStatus = slaStatus;
        this.slaBreached = slaBreached;
        this.firstResponseDueAt = firstResponseDueAt;
        this.resolutionDueAt = resolutionDueAt;
        this.firstRespondedAt = firstRespondedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.resolvedAt = resolvedAt;
        this.isNew = isNew;
        this.createdAtHuman = createdAtHuman;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getReferenceNumber() { return referenceNumber; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; }
    public String getTicketNumber() { return ticketNumber; }
    public void setTicketNumber(String ticketNumber) { this.ticketNumber = ticketNumber; }
    public UUID getContactId() { return contactId; }
    public void setContactId(UUID contactId) { this.contactId = contactId; }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getContactWaId() { return contactWaId; }
    public void setContactWaId(String contactWaId) { this.contactWaId = contactWaId; }
    public String getSubmitterName() { return submitterName; }
    public void setSubmitterName(String submitterName) { this.submitterName = submitterName; }
    public String getSubmitterEmail() { return submitterEmail; }
    public void setSubmitterEmail(String submitterEmail) { this.submitterEmail = submitterEmail; }
    public String getSubmitterPhone() { return submitterPhone; }
    public void setSubmitterPhone(String submitterPhone) { this.submitterPhone = submitterPhone; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Ticket.TicketStatus getStatus() { return status; }
    public void setStatus(Ticket.TicketStatus status) { this.status = status; }
    public Ticket.TicketPriority getPriority() { return priority; }
    public void setPriority(Ticket.TicketPriority priority) { this.priority = priority; }
    public Ticket.TicketSource getSource() { return source; }
    public void setSource(Ticket.TicketSource source) { this.source = source; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public UUID getAssignedToId() { return assignedToId; }
    public void setAssignedToId(UUID assignedToId) { this.assignedToId = assignedToId; }
    public String getAssignedToName() { return assignedToName; }
    public void setAssignedToName(String assignedToName) { this.assignedToName = assignedToName; }
    public List<TicketCommentDTO> getComments() { return comments; }
    public void setComments(List<TicketCommentDTO> comments) { this.comments = comments; }
    public String getSlaStatus() { return slaStatus; }
    public void setSlaStatus(String slaStatus) { this.slaStatus = slaStatus; }
    public boolean isSlaBreached() { return slaBreached; }
    public void setSlaBreached(boolean slaBreached) { this.slaBreached = slaBreached; }
    public LocalDateTime getFirstResponseDueAt() { return firstResponseDueAt; }
    public void setFirstResponseDueAt(LocalDateTime firstResponseDueAt) { this.firstResponseDueAt = firstResponseDueAt; }
    public LocalDateTime getResolutionDueAt() { return resolutionDueAt; }
    public void setResolutionDueAt(LocalDateTime resolutionDueAt) { this.resolutionDueAt = resolutionDueAt; }
    public LocalDateTime getFirstRespondedAt() { return firstRespondedAt; }
    public void setFirstRespondedAt(LocalDateTime firstRespondedAt) { this.firstRespondedAt = firstRespondedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public boolean isNew() { return isNew; }
    public void setNew(boolean isNew) { this.isNew = isNew; }
    public String getCreatedAtHuman() { return createdAtHuman; }
    public void setCreatedAtHuman(String createdAtHuman) { this.createdAtHuman = createdAtHuman; }

    public static TicketDTOBuilder builder() { return new TicketDTOBuilder(); }

    public static class TicketDTOBuilder {
        private UUID id;
        private String referenceNumber;
        private String ticketNumber;
        private UUID contactId;
        private String contactName;
        private String contactWaId;
        private String submitterName;
        private String submitterEmail;
        private String submitterPhone;
        private String subject;
        private String description;
        private Ticket.TicketStatus status;
        private Ticket.TicketPriority priority;
        private Ticket.TicketSource source;
        private String category;
        private UUID assignedToId;
        private String assignedToName;
        private List<TicketCommentDTO> comments;
        private String slaStatus;
        private boolean slaBreached;
        private LocalDateTime firstResponseDueAt;
        private LocalDateTime resolutionDueAt;
        private LocalDateTime firstRespondedAt;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private LocalDateTime resolvedAt;
        private boolean isNew;
        private String createdAtHuman;

        public TicketDTOBuilder id(UUID id) { this.id = id; return this; }
        public TicketDTOBuilder referenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; return this; }
        public TicketDTOBuilder ticketNumber(String ticketNumber) { this.ticketNumber = ticketNumber; return this; }
        public TicketDTOBuilder contactId(UUID contactId) { this.contactId = contactId; return this; }
        public TicketDTOBuilder contactName(String contactName) { this.contactName = contactName; return this; }
        public TicketDTOBuilder contactWaId(String contactWaId) { this.contactWaId = contactWaId; return this; }
        public TicketDTOBuilder submitterName(String submitterName) { this.submitterName = submitterName; return this; }
        public TicketDTOBuilder submitterEmail(String submitterEmail) { this.submitterEmail = submitterEmail; return this; }
        public TicketDTOBuilder submitterPhone(String submitterPhone) { this.submitterPhone = submitterPhone; return this; }
        public TicketDTOBuilder subject(String subject) { this.subject = subject; return this; }
        public TicketDTOBuilder description(String description) { this.description = description; return this; }
        public TicketDTOBuilder status(Ticket.TicketStatus status) { this.status = status; return this; }
        public TicketDTOBuilder priority(Ticket.TicketPriority priority) { this.priority = priority; return this; }
        public TicketDTOBuilder source(Ticket.TicketSource source) { this.source = source; return this; }
        public TicketDTOBuilder category(String category) { this.category = category; return this; }
        public TicketDTOBuilder assignedToId(UUID assignedToId) { this.assignedToId = assignedToId; return this; }
        public TicketDTOBuilder assignedToName(String assignedToName) { this.assignedToName = assignedToName; return this; }
        public TicketDTOBuilder comments(List<TicketCommentDTO> comments) { this.comments = comments; return this; }
        public TicketDTOBuilder slaStatus(String slaStatus) { this.slaStatus = slaStatus; return this; }
        public TicketDTOBuilder slaBreached(boolean slaBreached) { this.slaBreached = slaBreached; return this; }
        public TicketDTOBuilder firstResponseDueAt(LocalDateTime firstResponseDueAt) { this.firstResponseDueAt = firstResponseDueAt; return this; }
        public TicketDTOBuilder resolutionDueAt(LocalDateTime resolutionDueAt) { this.resolutionDueAt = resolutionDueAt; return this; }
        public TicketDTOBuilder firstRespondedAt(LocalDateTime firstRespondedAt) { this.firstRespondedAt = firstRespondedAt; return this; }
        public TicketDTOBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public TicketDTOBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public TicketDTOBuilder resolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; return this; }
        public TicketDTOBuilder isNew(boolean isNew) { this.isNew = isNew; return this; }
        public TicketDTOBuilder createdAtHuman(String createdAtHuman) { this.createdAtHuman = createdAtHuman; return this; }

        public TicketDTO build() {
            return new TicketDTO(id, referenceNumber, ticketNumber, contactId, contactName, contactWaId, submitterName, submitterEmail, submitterPhone, subject, description, status, priority, source, category, assignedToId, assignedToName, comments, slaStatus, slaBreached, firstResponseDueAt, resolutionDueAt, firstRespondedAt, createdAt, updatedAt, resolvedAt, isNew, createdAtHuman);
        }
    }
}
