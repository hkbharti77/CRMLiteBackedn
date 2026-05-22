package com.chatcrmlite.backend.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import java.io.Serializable;

public class ReminderDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private UUID id;
    private UUID leadId;
    private String message;
    private LocalDateTime dueDate;
    private boolean isCompleted;

    public ReminderDTO() {}

    public ReminderDTO(UUID id, UUID leadId, String message, LocalDateTime dueDate, boolean isCompleted) {
        this.id = id;
        this.leadId = leadId;
        this.message = message;
        this.dueDate = dueDate;
        this.isCompleted = isCompleted;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getLeadId() { return leadId; }
    public void setLeadId(UUID leadId) { this.leadId = leadId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getDueDate() { return dueDate; }
    public void setDueDate(LocalDateTime dueDate) { this.dueDate = dueDate; }
    public boolean isCompleted() { return isCompleted; }
    public void setCompleted(boolean isCompleted) { this.isCompleted = isCompleted; }

    public static ReminderDTOBuilder builder() {
        return new ReminderDTOBuilder();
    }

    public static class ReminderDTOBuilder {
        private UUID id;
        private UUID leadId;
        private String message;
        private LocalDateTime dueDate;
        private boolean isCompleted;

        public ReminderDTOBuilder id(UUID id) { this.id = id; return this; }
        public ReminderDTOBuilder leadId(UUID leadId) { this.leadId = leadId; return this; }
        public ReminderDTOBuilder message(String message) { this.message = message; return this; }
        public ReminderDTOBuilder dueDate(LocalDateTime dueDate) { this.dueDate = dueDate; return this; }
        public ReminderDTOBuilder isCompleted(boolean isCompleted) { this.isCompleted = isCompleted; return this; }

        public ReminderDTO build() {
            return new ReminderDTO(id, leadId, message, dueDate, isCompleted);
        }
    }
}
