package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ticket_activities")
public class TicketActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    private String userName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActivityType activityType;

    private String oldValue;
    private String newValue;

    @Column(columnDefinition = "TEXT")
    private String details;

    private LocalDateTime createdAt = LocalDateTime.now();

    public TicketActivity() {}

    public TicketActivity(UUID id, Ticket ticket, User user, String userName, ActivityType activityType, String oldValue, String newValue, String details, LocalDateTime createdAt) {
        this.id = id;
        this.ticket = ticket;
        this.user = user;
        this.userName = userName;
        this.activityType = activityType;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.details = details;
        this.createdAt = (createdAt != null) ? createdAt : LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Ticket getTicket() { return ticket; }
    public void setTicket(Ticket ticket) { this.ticket = ticket; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public ActivityType getActivityType() { return activityType; }
    public void setActivityType(ActivityType activityType) { this.activityType = activityType; }
    public String getOldValue() { return oldValue; }
    public void setOldValue(String oldValue) { this.oldValue = oldValue; }
    public String getNewValue() { return newValue; }
    public void setNewValue(String newValue) { this.newValue = newValue; }
    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public enum ActivityType {
        CREATED,
        STATUS_CHANGED,
        PRIORITY_CHANGED,
        ASSIGNED,
        UNASSIGNED,
        COMMENT_ADDED,
        DELETED,
        RESTORED,
        CATEGORY_CHANGED,
        SLA_BREACHED
    }

    public static TicketActivityBuilder builder() { return new TicketActivityBuilder(); }

    public static class TicketActivityBuilder {
        private UUID id;
        private Ticket ticket;
        private User user;
        private String userName;
        private ActivityType activityType;
        private String oldValue;
        private String newValue;
        private String details;
        private LocalDateTime createdAt = LocalDateTime.now();

        public TicketActivityBuilder id(UUID id) { this.id = id; return this; }
        public TicketActivityBuilder ticket(Ticket ticket) { this.ticket = ticket; return this; }
        public TicketActivityBuilder user(User user) { this.user = user; return this; }
        public TicketActivityBuilder userName(String userName) { this.userName = userName; return this; }
        public TicketActivityBuilder activityType(ActivityType activityType) { this.activityType = activityType; return this; }
        public TicketActivityBuilder oldValue(String oldValue) { this.oldValue = oldValue; return this; }
        public TicketActivityBuilder newValue(String newValue) { this.newValue = newValue; return this; }
        public TicketActivityBuilder details(String details) { this.details = details; return this; }
        public TicketActivityBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }

        public TicketActivity build() {
            return new TicketActivity(id, ticket, user, userName, activityType, oldValue, newValue, details, createdAt);
        }
    }
}
