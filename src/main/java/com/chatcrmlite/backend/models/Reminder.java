package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

import java.io.Serializable;

@Entity
public class Reminder implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id", nullable = false)
    private Lead lead;

    private String message;

    private LocalDateTime dueDate;

    private boolean isCompleted;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    private LocalDateTime createdAt;

    public Reminder() {}

    public Reminder(UUID id, Lead lead, String message, LocalDateTime dueDate, boolean isCompleted, User owner, LocalDateTime createdAt) {
        this.id = id;
        this.lead = lead;
        this.message = message;
        this.dueDate = dueDate;
        this.isCompleted = isCompleted;
        this.owner = owner;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Lead getLead() { return lead; }
    public void setLead(Lead lead) { this.lead = lead; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getDueDate() { return dueDate; }
    public void setDueDate(LocalDateTime dueDate) { this.dueDate = dueDate; }
    public boolean isCompleted() { return isCompleted; }
    public void setCompleted(boolean isCompleted) { this.isCompleted = isCompleted; }
    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public static ReminderBuilder builder() {
        return new ReminderBuilder();
    }

    public static class ReminderBuilder {
        private UUID id;
        private Lead lead;
        private String message;
        private LocalDateTime dueDate;
        private boolean isCompleted;
        private User owner;
        private LocalDateTime createdAt;

        public ReminderBuilder id(UUID id) { this.id = id; return this; }
        public ReminderBuilder lead(Lead lead) { this.lead = lead; return this; }
        public ReminderBuilder message(String message) { this.message = message; return this; }
        public ReminderBuilder dueDate(LocalDateTime dueDate) { this.dueDate = dueDate; return this; }
        public ReminderBuilder isCompleted(boolean isCompleted) { this.isCompleted = isCompleted; return this; }
        public ReminderBuilder owner(User owner) { this.owner = owner; return this; }
        public ReminderBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }

        public Reminder build() {
            return new Reminder(id, lead, message, dueDate, isCompleted, owner, createdAt);
        }
    }
}
